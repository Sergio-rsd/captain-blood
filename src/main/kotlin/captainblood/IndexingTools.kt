package captainblood

import agent.indexing.DocumentIndex
import agent.indexing.DocumentReader
import agent.indexing.EmbedTask
import agent.indexing.EmbeddingClient
import agent.indexing.FixedChunker
import agent.indexing.StructuralChunker
import agent.indexing.TelegramChatReader
import agent.indexing.TgThread
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Пополнение корпуса новой информацией в разных форматах — документ (`.txt`/`.md`/`.pdf`/
 * `.docx`/`.doc`) или экспорт переписки Telegram Desktop (JSON). Команды REPL `!index-doc`/
 * `!index-telegram` в `captainblood.Main` вызывают функции этого файла, затем
 * `syncDbToVps` переносит обновлённую базу на VPS — пополнять базу можно постоянно, без
 * пересборки/передеплоя сервиса (RAG открывает базу заново на каждый вопрос).
 */

private data class OptScore(val index: Int, val score: Int)

private val NOISE_JUDGE_SYSTEM = """
Ты — компонент фильтрации шума при индексации базы знаний из переписки телеграм-канала.
Тебе дают пронумерованный список тредов (связных фрагментов переписки: вопрос-ответ,
обсуждение). Для КАЖДОГО треда оцени его САМОСТОЯТЕЛЬНУЮ информационную ценность для базы
знаний по шкале от 0 до 10:
  0-2  — чистый флуд/оффтоп/бытовой разговор, обмен эмоциями, реакции
  3-4  — околоделовой разговор без содержательных деталей
  5-6  — есть тема по существу, но без содержательных деталей
  7-8  — содержит конкретный полезный факт, совет, объяснение или историю
  9-10 — исчерпывающее, детальное объяснение/история

Верни оценку для КАЖДОГО треда из списка — ни одного не пропускай.
Отвечай ТОЛЬКО JSON-массивом, без пояснений, без markdown, в формате:
[{"index": 1, "score": 7}, {"index": 2, "score": 0}, ...]
""".trimIndent()

private const val NOISE_JUDGE_BATCH_SIZE = 15

/** Собирает промпт для батча LLM-судьи шума: пронумерованный список тредов. */
private fun buildNoiseJudgePrompt(batch: List<TgThread>): String {
    val sb = StringBuilder("Треды:\n\n")
    batch.forEachIndexed { i, t ->
        sb.append("[${i + 1}]\n")
        sb.append(t.text.take(1200))
        sb.append("\n\n---\n\n")
    }
    return sb.toString()
}

/** Парсит JSON-массив оценок из ответа LLM-судьи. */
private fun parseScoreResponse(raw: String): List<OptScore>? {
    var content = raw.trim().removePrefix("﻿")
    content = content.replace(Regex("```(?:json)?\\s*\\n?", RegexOption.IGNORE_CASE), "").replace("```", "").trim()

    val firstBracket = content.indexOf('[')
    val lastBracket = content.lastIndexOf(']')
    if (firstBracket < 0 || lastBracket <= firstBracket) {
        System.err.println("  [Judge] ⚠ JSON-массив не найден в ответе LLM. Сырой ответ: «${content.take(200)}»")
        return null
    }
    content = content.substring(firstBracket, lastBracket + 1)

    return try {
        val arr = JSONArray(content)
        if (arr.length() > 0 && arr.opt(0) !is JSONObject) {
            (0 until arr.length()).map { i -> OptScore(index = i + 1, score = arr.optInt(i, 0)) }
        } else {
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                OptScore(index = obj.optInt("index", i + 1), score = obj.optInt("score", 0))
            }
        }
    } catch (e: JSONException) {
        System.err.println("  [Judge] ⚠ ошибка парсинга JSON: ${e.message}")
        null
    }
}

/** Слой 2 фильтра шума при индексации Telegram-экспорта — LLM-судья информативности, батчами. */
private fun judgeNoiseWithLlm(threads: List<TgThread>, localModel: String, threshold: Int): List<TgThread> {
    if (threads.isEmpty()) return emptyList()
    val kept = mutableListOf<TgThread>()
    val batches = threads.chunked(NOISE_JUDGE_BATCH_SIZE)
    batches.forEachIndexed { bi, batch ->
        print("\r  LLM-судья шума: батч ${bi + 1}/${batches.size} (всего тредов: ${threads.size})")
        val scores = try {
            val r = generateGuarded(localModel, buildNoiseJudgePrompt(batch), NOISE_JUDGE_SYSTEM, 0.0)
            if (r.error != null) { System.err.println("\n  [Noise] ⚠ ошибка вызова LLM (${r.error})"); null }
            else parseScoreResponse(r.text)
        } catch (e: Exception) {
            System.err.println("\n  [Noise] ⚠ ошибка вызова LLM (${e.message})")
            null
        }
        if (scores == null) {
            System.err.println("\n  [Noise] батч ${bi + 1}: fallback — оставляю все треды батча (LLM не вернул валидный JSON)")
            kept += batch
        } else {
            val scoreMap = scores.associate { it.index to it.score }
            batch.forEachIndexed { i, thread -> if ((scoreMap[i + 1] ?: 0) >= threshold) kept += thread }
        }
    }
    println()
    return kept
}

private fun slugify(text: String): String = text
    .replace(Regex("[^a-zA-Zа-яА-Я0-9]"), "_")
    .replace(Regex("_+"), "_")
    .trim('_')
    .lowercase()

/**
 * Команда `!index-telegram` — добавляет в корпус экспорт Telegram Desktop (JSON), с
 * двухслойным фильтром шума (эвристики + LLM-судья).
 *
 * @param path          путь к JSON-файлу экспорта
 * @param index         открытый индекс базы
 * @param embClient     клиент эмбеддингов
 * @param localModel    локальная модель для LLM-судьи шума
 * @param noiseThreshold порог отсечения (0-10) — треды с оценкой ниже отбрасываются
 */
internal fun indexTelegramExport(path: String, index: DocumentIndex, embClient: EmbeddingClient, localModel: String, noiseThreshold: Int) {
    val file = File(path.removeSurrounding("\""))
    if (!file.exists()) { println("  Файл не найден: ${file.path}"); return }

    println("  Разбор экспорта...")
    val threads = try { TelegramChatReader.readThreads(file.path) } catch (e: Exception) {
        println("  Ошибка разбора: ${e.message}"); return
    }
    println("  Сообщений: ${threads.sumOf { it.messages.size }} → тредов: ${threads.size}")

    val afterHeuristics = TelegramChatReader.filterNoiseHeuristics(threads)
    println("  Слой 1 (эвристики): ${threads.size} → ${afterHeuristics.size} (отсеяно ${threads.size - afterHeuristics.size})")
    if (afterHeuristics.isEmpty()) { println("  Индексировать нечего — все треды отсеяны."); return }

    val channel = afterHeuristics.first().channel
    val slug = slugify(channel)
    val existing = index.existingTelegramChunks(channel)
    val (unchanged, toProcess) = afterHeuristics.partition { thread ->
        TelegramChatReader.buildChunks(thread, slug).all { existing[it.chunkId] == it.text }
    }
    println("  Уже проиндексировано без изменений: ${unchanged.size} тредов — пропускаю")
    if (toProcess.isEmpty()) { println("  Новых/изменившихся тредов нет — индекс уже актуален."); return }

    val afterJudge = judgeNoiseWithLlm(toProcess, localModel, noiseThreshold)
    println("  Слой 2 (LLM-судья ≥$noiseThreshold, модель $localModel): " +
            "${toProcess.size} → ${afterJudge.size} (отсеяно ${toProcess.size - afterJudge.size})")
    if (afterJudge.isEmpty()) { println("  Индексировать нечего — все новые/изменившиеся треды отсеяны."); return }

    var failed = 0
    var chunksSaved = 0
    afterJudge.forEachIndexed { i, thread ->
        for (chunk in TelegramChatReader.buildChunks(thread, slug)) {
            try {
                index.save(chunk.copy(embedding = embClient.embed(chunk.text, EmbedTask.DOCUMENT)))
                chunksSaved++
            } catch (e: Exception) {
                failed++
                System.err.println("\n  [Embed] ⚠ подчанк ${chunk.chunkId} пропущен (${e.message})")
            }
        }
        print("\r  Эмбеддинги: ${i + 1}/${afterJudge.size} тредов ($chunksSaved подчанков сохранено)")
    }
    println()
    val failedNote = if (failed > 0) ", не удалось встроить подчанков: $failed" else ""
    println("  Готово: канал «$channel» — ${afterJudge.size} тредов обработано ($chunksSaved подчанков)$failedNote, " +
            "${unchanged.size} тредов пропущено без изменений (всего чанков в базе: ${index.count()})")
}

/**
 * Команда `!index-doc` — добавляет в корпус документ (`.txt`/`.md`/`.pdf`/`.docx`/`.doc`),
 * чанкует обеими стратегиями (fixed + structural) и сохраняет с эмбеддингами.
 *
 * @param path      путь к файлу документа
 * @param index     открытый индекс базы
 * @param embClient клиент эмбеддингов
 */
internal fun indexDocument(path: String, index: DocumentIndex, embClient: EmbeddingClient) {
    val file = File(path.removeSurrounding("\""))
    if (!file.exists()) { println("  Файл не найден: ${file.path}"); return }

    val text = try { DocumentReader.readFile(file.path) } catch (e: Exception) {
        println("  Ошибка чтения: ${e.message}"); return
    }

    val title = file.nameWithoutExtension
    val source = file.name
    val fixed = FixedChunker.chunk(title, source, text)
    val struct = StructuralChunker.chunk(title, source, text)
    println("  Чанкинг: fixed=${fixed.size}  structural=${struct.size}")

    val all = fixed + struct
    all.forEachIndexed { i, chunk ->
        index.save(chunk.copy(embedding = embClient.embed(chunk.text, EmbedTask.DOCUMENT)))
        print("\r  Эмбеддинги: ${i + 1}/${all.size}")
    }
    println()
    println("  Готово: «$source» добавлен в базу (всего чанков: ${index.count()})")
}

/**
 * Команда `!reembed-all` — пересчитывает эмбеддинги ВСЕХ чанков в базе текущей моделью
 * [EmbeddingClient] (текст чанков и вся остальная метаинформация не трогаются, только
 * вектор). Нужна при смене embedding-модели — старые векторы из другой модели живут в
 * другом векторном пространстве, косинусное сходство с новыми query-векторами для них
 * бессмысленно.
 *
 * @param index     открытый индекс базы
 * @param embClient клиент эмбеддингов — уже сконфигурирован на нужную (новую) модель
 */
internal fun reembedAll(index: DocumentIndex, embClient: EmbeddingClient) {
    val chunks = index.allChunks()
    println("  Пересчитываю эмбеддинги для ${chunks.size} чанков...")
    var failed = 0
    chunks.forEachIndexed { i, chunk ->
        try {
            index.save(chunk.copy(embedding = embClient.embed(chunk.text, EmbedTask.DOCUMENT)))
        } catch (e: Exception) {
            failed++
            System.err.println("\n  [Reembed] ⚠ чанк ${chunk.chunkId} пропущен (${e.message})")
        }
        print("\r  Эмбеддинги: ${i + 1}/${chunks.size}")
    }
    println()
    val failedNote = if (failed > 0) " (не удалось: $failed)" else ""
    println("  Готово: пересчитано ${chunks.size - failed} чанков$failedNote.")
}
