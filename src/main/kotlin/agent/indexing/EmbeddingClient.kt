package agent.indexing

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * Задача, для которой генерируется эмбеддинг — nomic-embed-text асимметрична и
 * ожидает разные task-префиксы для индексируемого текста и для поискового запроса.
 */
enum class EmbedTask(val prefix: String) {
    DOCUMENT("search_document: "),
    QUERY("search_query: ")
}

/**
 * HTTP-клиент для получения эмбеддингов через Ollama API.
 *
 * Отправляет POST-запрос на локальный Ollama-сервер и возвращает
 * L2-нормализованный вектор эмбеддинга. Нормализация необходима для
 * корректного вычисления косинусного сходства (cosine = dot product
 * двух нормализованных векторов).
 *
 * @param ollamaUrl базовый URL Ollama (по умолчанию — локальный)
 * @param model     имя модели эмбеддингов
 */
class EmbeddingClient(
    private val ollamaUrl: String = "http://localhost:11434",
    private val model: String = "bge-m3"
) {
    companion object {
        /**
         * Реальный контекст `nomic-embed-text` — 2048 токенов (`ollama show nomic-embed-text`
         * → `context length: 2048`), НЕ 8192 — 8192 в выводе `ollama show` это дефолт
         * ПАРАМЕТРА `num_ctx` из конфига модели, а не архитектурный потолок; форсировать
         * `num_ctx` выше реального контекста в запросе бесполезно, сервер всё равно
         * отвечает `HTTP 500 the input length exceeds the context length` (проверено
         * эмпирически: реальный тред из Telegram-экспорта на 5581 символ стабильно падал
         * уже на 2600 символах, при 2500 — проходил). При кириллице токенизация ощутимо
         * менее эффективна, чем при английском тексте (та же природа проблемы, что и с
         * `mxbai-embed-large` — там контекст 512 давал практический лимит ~600 символов;
         * пропорция 2048/512 ≈ 4 примерно совпадает с 2500/600 ≈ 4 здесь). 2200 — с
         * запасом ниже найденной
         * эмпирической границы (2500 OK / 2600 FAIL), т.к. плотность токенизации зависит
         * от конкретного текста (доля кириллицы/латиницы/пунктуации).
         */
        private const val MAX_CHARS = 2200
    }

    /**
     * Генерирует L2-нормализованный вектор эмбеддинга для текста.
     *
     * nomic-embed-text дообучена под task-префиксы: `"search_document: "` для текста,
     * который индексируется, `"search_query: "` для текста поискового запроса. Без
     * префикса эмбеддинги валидны, но хуже разделяют релевантное от нерелевантного.
     *
     * @param text входной текст (чанк документа или вопрос)
     * @param task роль текста — [EmbedTask.DOCUMENT] при индексации, [EmbedTask.QUERY] при поиске
     * @return FloatArray размерности 768
     */
    fun embed(text: String, task: EmbedTask): FloatArray {
        val truncated = safeTruncate(text, MAX_CHARS)
        // Task-префиксы — конвенция, специфичная под nomic-embed-text; другие модели
        // (bge-m3 и т.п.) под неё не тюнены, префикс им только мешает.
        val prompt = if (model == "nomic-embed-text") task.prefix + truncated else truncated
        val body = JSONObject().put("model", model).put("prompt", prompt).toString()
        val response = httpPost("$ollamaUrl/api/embeddings", body)
        val arr = JSONObject(response).getJSONArray("embedding")
        val vector = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        return normalize(vector)
    }

    /**
     * Проверяет доступность Ollama-сервера тестовым запросом.
     *
     * @return true если сервер отвечает
     */
    fun isAvailable(): Boolean = try {
        embed("ping", EmbedTask.QUERY)
        true
    } catch (_: Exception) { false }

    /**
     * Обрезает текст до [maxChars] символов, не разрывая суррогатную пару UTF-16
     * (эмодзи и другие символы вне Basic Multilingual Plane занимают 2 code unit'а).
     * `String.take(n)` режет по индексу code unit'а вслепую — на тексте с большим
     * количеством эмодзи (реальный случай: экспорт Telegram-чата) обрезка ровно
     * посередине суррогатной пары даёт невалидный UTF-8 при кодировании и Ollama
     * отвечает `HTTP 500` на `/api/embeddings`.
     */
    private fun safeTruncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val sumSq = v.fold(0.0) { acc, x -> acc + x.toDouble() * x.toDouble() }
        val norm = sqrt(sumSq).toFloat()
        return if (norm < 1e-9f) v else FloatArray(v.size) { v[it] / norm }
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code != 200) error("Ollama HTTP $code для URL $urlStr")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }
}
