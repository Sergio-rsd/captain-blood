package agent.indexing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Одно сообщение из экспорта Telegram Desktop (`result.json`, «Экспорт истории чата»).
 *
 * @param id           идентификатор сообщения в чате
 * @param author       имя отправителя
 * @param text         текст сообщения (уже сведён из строки/массива entity-объектов к плоской строке)
 * @param epochSeconds время отправки (`date_unixtime` из экспорта)
 * @param replyToId    id сообщения, на которое это является ответом (null — не ответ)
 * @param hasMedia     true, если к сообщению приложено медиа (`photo`/`file`/`media_type`) —
 *                     сами медиафайлы не читаются, учитывается только текст/подпись рядом с ними
 */
data class TgMessage(
    val id: Long,
    val author: String,
    val text: String,
    val epochSeconds: Long,
    val replyToId: Long?,
    val hasMedia: Boolean
)

/**
 * Тред — группа сообщений, объединённых цепочкой ответов (`reply_to_message_id`) вокруг
 * одного корневого сообщения. Тред без единого ответа (одиночное сообщение без реакций)
 * тоже валиден — это тред из одного сообщения.
 *
 * @param rootId  id корневого сообщения треда
 * @param channel название канала/чата-источника (поле `name` экспорта)
 * @param messages сообщения треда в хронологическом порядке
 */
data class TgThread(val rootId: Long, val channel: String, val messages: List<TgMessage>) {
    /** Текст треда, собранный построчно «Автор: текст» — единица для LLM-судьи и индексации. */
    val text: String get() = messages.joinToString("\n") { "${it.author}: ${it.text}" }
}

/**
 * Чтение и подготовка корпуса из экспорта Telegram Desktop для RAG-индексации доменного
 * ассистента (Week 6 День 27).
 *
 * Экспорт (`result.json`) — JSON с полем `messages`: у каждого сообщения `type`
 * ("message"/"service" — служебные вступления/выходы из чата пропускаются), `text`
 * (строка ИЛИ массив строк/entity-объектов при форматированном тексте — ссылки,
 * упоминания и т.п.), опционально `reply_to_message_id`, и признаки медиа
 * (`media_type`/`photo`/`file`) — сами медиафайлы не читаются и не анализируются,
 * учитывается только текст/подпись рядом с ними.
 *
 * Сообщения группируются в треды по цепочке `reply_to_message_id` (резолв до корня, а
 * не только прямой родитель) — это даёт связные смысловые единицы («вопрос → ответ →
 * уточнение») вместо разрозненных реплик, что резко поднимает сигнал/шум по сравнению
 * с индексацией по одному сообщению. Дальше треды проходят эвристический фильтр шума
 * (Слой 1, см. [filterNoiseHeuristics]) — без LLM; Слой 2 (LLM-судья информативности,
 * локальная модель) реализован в вызывающем коде (`Week6Day2LocLLMApp.kt`), а не здесь,
 * т.к. требует HTTP-вызова к Ollama.
 */
object TelegramChatReader {

    private const val MAX_CHAIN_DEPTH = 5
    private const val MIN_THREAD_LENGTH = 40
    private const val MIN_LETTER_COUNT = 20

    /**
     * Безопасный размер подчанка длинного треда — заметно ниже реального лимита
     * эмбеддингов ([EmbeddingClient] `MAX_CHARS` = 2200 символов, найдено эмпирически:
     * реальный контекст `nomic-embed-text` — 2048 токенов, кириллица токенизируется
     * неэффективно). Оставляет запас, т.к. плотность токенизации у разных сообщений разная.
     */
    private const val THREAD_CHUNK_SIZE = 1500

    /**
     * Стратегия для тредов, чьё ПЕРВОЕ сообщение — объявление задания дня («🔥 День N. ...»
     * от менеджера курса). Найдено эмпирически (Week 6 День 28): такие чанки конкурируют
     * за embedding-ранг с сотнями обычных реплик участников на ту же повторяющуюся лексику
     * курса («задание», «индексация», «RAG») и могут занимать место далеко за пределами
     * топ-K обычного поиска (реальный случай: День 21 — 151-е место из 1658). Среди ТОЛЬКО
     * объявлений (~29 штук) та же модель эмбеддингов ранжирует их корректно (День 21 — 1-е
     * место из 29) — конкурентов на порядки меньше, и темы дней различимы между собой.
     * Отдельная стратегия позволяет ретривелу ДОПОЛНИТЕЛЬНО искать в этом маленьком чистом
     * пуле, не заменяя обычный поиск (см. `cmpRetrieve` в `Week6Day3LocLLMwithRAG.kt`).
     */
    private const val DAY_ANNOUNCEMENT_STRATEGY = "day_announcement"

    /** Признак объявления задания дня — эмодзи 🔥 и слово «День» в начале первого сообщения треда. */
    private val DAY_ANNOUNCEMENT_PATTERN = Regex("^\\s*🔥\\s*День\\s+\\d+", RegexOption.IGNORE_CASE)

    /** Определяет, является ли ПЕРВОЕ сообщение треда объявлением задания дня. */
    private fun isDayAnnouncement(thread: TgThread): Boolean =
        thread.messages.firstOrNull()?.text?.let { DAY_ANNOUNCEMENT_PATTERN.containsMatchIn(it) } ?: false

    /**
     * Разбирает экспорт Telegram Desktop и группирует сообщения в треды.
     *
     * @param path путь к `result.json`
     * @return треды, готовые к эвристическому фильтру шума и дальнейшей LLM-оценке
     */
    fun readThreads(path: String): List<TgThread> {
        val root = JSONObject(File(path).readText(Charsets.UTF_8))
        val channel = root.optString("name", File(path).nameWithoutExtension)
        val messagesJson = root.getJSONArray("messages")

        val messages = mutableListOf<TgMessage>()
        for (i in 0 until messagesJson.length()) {
            val m = messagesJson.getJSONObject(i)
            if (m.optString("type") != "message") continue
            messages += TgMessage(
                id = m.getLong("id"),
                author = m.optString("from", "Unknown"),
                text = extractText(m.opt("text")),
                epochSeconds = m.optString("date_unixtime", "0").toLongOrNull() ?: 0L,
                replyToId = if (m.has("reply_to_message_id")) m.optLong("reply_to_message_id") else null,
                hasMedia = m.has("media_type") || m.has("photo") || m.has("file")
            )
        }

        return groupIntoThreads(messages, channel)
    }

    /**
     * Отсеивает явный шум эвристиками (Слой 1, без LLM): треды короче порога длины,
     * треды почти без буквенных символов (реакции из эмодзи/пунктуации), точные
     * текстовые дубликаты (флуд-копипаста — оставляется первое вхождение).
     *
     * @param threads треды после группировки ([readThreads])
     * @return треды, прошедшие эвристический фильтр — кандидаты на оценку LLM-судьёй
     */
    fun filterNoiseHeuristics(threads: List<TgThread>): List<TgThread> {
        val seenText = mutableSetOf<String>()
        return threads.filter { thread ->
            when {
                thread.text.length < MIN_THREAD_LENGTH -> false
                thread.text.count { it.isLetter() } < MIN_LETTER_COUNT -> false
                !seenText.add(thread.text) -> false
                else -> true
            }
        }
    }

    /**
     * Строит чанки для индексации одного треда — разбивает длинный тред на подчанки
     * ПО ГРАНИЦАМ СООБЩЕНИЙ (не разрывая сообщение посередине), чтобы каждый подчанк
     * помещался в реальный контекст модели эмбеддингов (см. [THREAD_CHUNK_SIZE]).
     *
     * Короткий тред (умещается в [THREAD_CHUNK_SIZE] целиком — подавляющее большинство,
     * средняя длина треда в реальном экспорте заметно меньше лимита) возвращает ОДИН
     * чанк с `chunk_id` БЕЗ суффикса части (`tg_<slug>_thread_<rootId>`) — тем же, что
     * был до появления подчанкинга, чтобы не инвалидировать дедуп ([DocumentIndex.
     * existingTelegramChunks]) уже проиндексированных коротких тредов. Только длинные
     * треды (которые раньше индексировались одним обрезанным чанком, теряя хвост
     * обсуждения) получают новую схему `..._p0`, `..._p1`, ... — их дедуп при первом
     * запуске после обновления НЕ сработает (старый `chunk_id` без суффикса не совпадёт
     * ни с одним новым), они переиндексируются один раз; старая обрезанная запись в базе
     * остаётся осиротевшей строкой (`DocumentIndex` не умеет удалять осиротевшие чанки —
     * тот же известный порог упрощения, что и в других местах проекта).
     *
     * @param thread  тред для разбиения
     * @param slug    slug канала (используется в `chunk_id`)
     * @return чанки, готовые к сохранению (без эмбеддинга — заполняется вызывающим кодом)
     */
    fun buildChunks(thread: TgThread, slug: String): List<Chunk> {
        val lines = thread.messages.map { "${it.author}: ${it.text}" }
        val parts = mutableListOf<String>()
        val buffer = StringBuilder()
        for (line in lines) {
            if (buffer.isNotEmpty() && buffer.length + line.length + 1 > THREAD_CHUNK_SIZE) {
                parts += buffer.toString()
                buffer.clear()
            }
            if (line.length > THREAD_CHUNK_SIZE) {
                if (buffer.isNotEmpty()) { parts += buffer.toString(); buffer.clear() }
                var idx = 0
                while (idx < line.length) {
                    parts += line.substring(idx, minOf(idx + THREAD_CHUNK_SIZE, line.length))
                    idx += THREAD_CHUNK_SIZE
                }
                continue
            }
            if (buffer.isNotEmpty()) buffer.append("\n")
            buffer.append(line)
        }
        if (buffer.isNotEmpty()) parts += buffer.toString()
        val strategy = if (isDayAnnouncement(thread)) DAY_ANNOUNCEMENT_STRATEGY else "telegram_thread"

        return if (parts.size <= 1) {
            listOf(
                Chunk(
                    chunkId = "tg_${slug}_thread_${thread.rootId}",
                    source = thread.channel,
                    title = thread.channel,
                    section = "thread_${thread.rootId}",
                    strategy = strategy,
                    text = parts.firstOrNull() ?: thread.text
                )
            )
        } else {
            parts.mapIndexed { i, text ->
                Chunk(
                    chunkId = "tg_${slug}_thread_${thread.rootId}_p$i",
                    source = thread.channel,
                    title = thread.channel,
                    section = "thread_${thread.rootId}_p$i",
                    strategy = strategy,
                    text = text
                )
            }
        }
    }

    /**
     * Извлекает плоский текст из поля `text` экспорта — оно бывает простой строкой ИЛИ
     * массивом строк/entity-объектов `{"type", "text"}` (форматированный текст: ссылки,
     * упоминания, жирный шрифт и т.п.) — элементы конкатенируются в порядке следования.
     */
    private fun extractText(raw: Any?): String = when (raw) {
        is String -> raw
        is JSONArray -> (0 until raw.length()).joinToString("") { i ->
            when (val el = raw.get(i)) {
                is String -> el
                is JSONObject -> el.optString("text", "")
                else -> ""
            }
        }
        else -> ""
    }.trim()

    /** Резолвит корень цепочки ответов для сообщения (глубина ограничена, защита от циклов). */
    private fun resolveRoot(id: Long, byId: Map<Long, TgMessage>): Long {
        var current = byId[id] ?: return id
        var depth = 0
        val visited = mutableSetOf(id)
        while (current.replyToId != null && depth < MAX_CHAIN_DEPTH) {
            val parent = byId[current.replyToId] ?: break
            if (!visited.add(parent.id)) break // цикл — маловероятно, но на всякий случай
            current = parent
            depth++
        }
        return current.id
    }

    /** Группирует сообщения в треды по резолвленному корню, сортирует по времени внутри треда. */
    private fun groupIntoThreads(messages: List<TgMessage>, channel: String): List<TgThread> {
        val byId = messages.associateBy { it.id }
        val groups = linkedMapOf<Long, MutableList<TgMessage>>()
        for (m in messages) {
            val root = resolveRoot(m.id, byId)
            groups.getOrPut(root) { mutableListOf() } += m
        }
        return groups.map { (rootId, msgs) -> TgThread(rootId, channel, msgs.sortedBy { it.epochSeconds }) }
    }
}
