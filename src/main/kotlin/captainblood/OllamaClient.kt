package captainblood

import agent.mcp.McpTool
import client.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Базовый URL локального сервера Ollama. */
private const val OLLAMA_URL = "http://localhost:11434"

/**
 * Системный промпт против языкового дрейфа локальных моделей (реальный дефект
 * `qwen2.5:7b-instruct` — модель может съехать на китайский или другой язык посреди
 * генерации, особенно на длинных ответах).
 */
internal const val RUSSIAN_LANGUAGE_GUARD =
    "Отвечай строго на русском языке от начала до конца. Даже если тебе покажется уместным " +
        "переключиться на другой язык (например, английский или китайский) — не делай этого, " +
        "это ошибка, а не особенность."

/** Символы CJK (китайский/японский/корейский) — признак языкового дрейфа. */
internal val CJK_REGEX = Regex("[一-鿿぀-ヿ가-힯]")

private const val MAX_LANGUAGE_DRIFT_RETRIES = 2

/**
 * Результат одного запроса к локальной модели.
 *
 * @param model       имя модели Ollama
 * @param text        текст ответа (пустой при ошибке)
 * @param elapsedMs   время генерации в миллисекундах
 * @param inputTokens число входных токенов (`prompt_eval_count` из ответа Ollama, 0 если отсутствует)
 * @param outputTokens число выходных токенов (`eval_count` из ответа Ollama, 0 если отсутствует)
 * @param error       текст ошибки, если запрос не удался
 */
data class LocalGenResult(
    val model: String,
    val text: String,
    val elapsedMs: Long,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val error: String? = null
)

/**
 * Результат одного вызова `/api/chat` с поддержкой tool-calling.
 *
 * @param content      текст финального ответа модели (пустой, если модель запросила инструмент)
 * @param toolCalls     запрошенные вызовы инструментов (пусто — модель ответила текстом, без инструментов)
 * @param elapsedMs    время генерации в миллисекундах
 * @param inputTokens  число входных токенов (`prompt_eval_count`, 0 если отсутствует)
 * @param outputTokens число выходных токенов (`eval_count`, 0 если отсутствует)
 * @param error        текст ошибки, если запрос не удался
 */
data class OllamaChatResult(
    val content: String,
    val toolCalls: List<LlmClient.LlmToolCall> = emptyList(),
    val elapsedMs: Long,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val error: String? = null
)

/**
 * HTTP-клиент к локальной Ollama для одной конкретной модели (генерация без стриминга).
 *
 * @param model    имя модели, установленной в Ollama (`ollama list`)
 * @param baseUrl  базовый URL сервера Ollama
 */
class OllamaClient(private val model: String, private val baseUrl: String = OLLAMA_URL) {

    /**
     * Генерирует ответ модели на промпт через `/api/generate`.
     *
     * @param prompt      текст запроса
     * @param system      системный промпт (опционально)
     * @param temperature температура генерации
     * @param numPredict  потолок токенов генерации (Ollama `num_predict`), `null` — дефолт Ollama
     * @param numCtx      размер окна контекста (Ollama `num_ctx`), `null` — дефолт Ollama
     * @return результат с текстом ответа и временем генерации (или ошибкой)
     */
    fun generate(
        prompt: String, system: String? = null, temperature: Double = 0.3,
        numPredict: Int? = null, numCtx: Int? = null
    ): LocalGenResult {
        val options = JSONObject().put("temperature", temperature)
        if (numPredict != null) options.put("num_predict", numPredict)
        if (numCtx != null) options.put("num_ctx", numCtx)
        val body = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("stream", false)
            .put("options", options)
        if (system != null) body.put("system", system)
        val start = System.currentTimeMillis()
        return try {
            val response = httpPost("$baseUrl/api/generate", body.toString())
            val elapsed = System.currentTimeMillis() - start
            val json = JSONObject(response)
            LocalGenResult(
                model = model,
                text = json.getString("response").trim(),
                elapsedMs = elapsed,
                inputTokens = json.optInt("prompt_eval_count", 0),
                outputTokens = json.optInt("eval_count", 0)
            )
        } catch (e: Exception) {
            LocalGenResult(model, "", System.currentTimeMillis() - start, error = e.message ?: "неизвестная ошибка")
        }
    }

    /**
     * Отправляет запрос с историей сообщений через `/api/chat` — в отличие от [generate],
     * поддерживает tool-calling (`tools`).
     *
     * @param history     история диалога
     * @param tools       список инструментов; пустой список — вызов без `tools` в теле запроса
     * @param system      системный промпт (опционально)
     * @param temperature температура генерации
     * @return текст ответа и/или запрошенные вызовы инструментов, либо ошибка
     */
    fun chat(
        history: List<LlmClient.ChatMessage>,
        tools: List<McpTool> = emptyList(),
        system: String? = null,
        temperature: Double = 0.3
    ): OllamaChatResult {
        val messages = JSONArray()
        if (system != null) messages.put(JSONObject().put("role", "system").put("content", system))
        for (msg in history) {
            val obj = when {
                msg.role == "tool" -> JSONObject()
                    .put("role", "tool")
                    .put("content", msg.content)
                    .apply { if (msg.toolCallId != null) put("tool_call_id", msg.toolCallId) }
                msg.rawToolCalls != null -> JSONObject()
                    .put("role", "assistant")
                    .put("content", msg.content)
                    .put("tool_calls", JSONArray(msg.rawToolCalls))
                else -> JSONObject().put("role", msg.role).put("content", msg.content)
            }
            messages.put(obj)
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
            .put("options", JSONObject().put("temperature", temperature))

        if (tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            for (tool in tools) {
                toolsArr.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function", JSONObject()
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", tool.inputSchema)
                        )
                )
            }
            body.put("tools", toolsArr)
        }

        val start = System.currentTimeMillis()
        return try {
            val response = httpPost("$baseUrl/api/chat", body.toString())
            val elapsed = System.currentTimeMillis() - start
            val json = JSONObject(response)
            val message = json.optJSONObject("message")
            OllamaChatResult(
                content = message?.optString("content", "") ?: "",
                toolCalls = parseOllamaToolCalls(message),
                elapsedMs = elapsed,
                inputTokens = json.optInt("prompt_eval_count", 0),
                outputTokens = json.optInt("eval_count", 0)
            )
        } catch (e: Exception) {
            OllamaChatResult(content = "", elapsedMs = System.currentTimeMillis() - start, error = e.message ?: "неизвестная ошибка")
        }
    }

    private fun parseOllamaToolCalls(message: JSONObject?): List<LlmClient.LlmToolCall> {
        val arr = message?.optJSONArray("tool_calls") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val tc = arr.optJSONObject(i) ?: return@mapNotNull null
            val fn = tc.optJSONObject("function") ?: return@mapNotNull null
            val args = fn.optJSONObject("arguments") ?: JSONObject()
            LlmClient.LlmToolCall(id = "local_call_$i", name = fn.optString("name"), input = args)
        }
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 5_000
        conn.readTimeout = 300_000
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code != 200) error("Ollama HTTP $code для модели $model")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }

    companion object {
        /**
         * Проверяет, поднят ли сервер Ollama (не требует конкретной модели).
         *
         * @param baseUrl базовый URL сервера
         * @return true, если сервер отвечает на `/api/tags`
         */
        fun isServerRunning(baseUrl: String = OLLAMA_URL): Boolean = try {
            val conn = URL("$baseUrl/api/tags").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }

        /**
         * Возвращает список имён моделей, установленных в Ollama.
         *
         * @param baseUrl базовый URL сервера
         * @return имена моделей (с тегами, как в `ollama list`) или пустой список при ошибке
         */
        fun listModels(baseUrl: String = OLLAMA_URL): List<String> = try {
            val conn = URL("$baseUrl/api/tags").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val models = JSONObject(text).getJSONArray("models")
            (0 until models.length()).map { models.getJSONObject(it).getString("name") }
        } catch (_: Exception) {
            emptyList()
        }

        /**
         * Проверяет, загружена ли конкретная модель в память Ollama прямо сейчас
         * (`GET /api/ps`), в отличие от [listModels], который просто перечисляет установленные.
         *
         * @param model   имя модели, точное соответствие тому, что вернёт `ollama ps`
         * @param baseUrl базовый URL сервера
         * @return true, если модель уже в памяти; false также при ошибке запроса
         */
        fun isModelLoaded(model: String, baseUrl: String = OLLAMA_URL): Boolean = try {
            val conn = URL("$baseUrl/api/ps").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val models = JSONObject(text).optJSONArray("models") ?: JSONArray()
            (0 until models.length()).any { models.getJSONObject(it).optString("name") == model }
        } catch (_: Exception) {
            false
        }

        /**
         * Сериализует вызовы инструментов обратно в JSON-массив для истории `/api/chat`.
         *
         * @param toolCalls вызовы инструментов, которые вернула модель на предыдущем шаге
         * @return JSON-строка массива `tool_calls` для поля `rawToolCalls` в `ChatMessage`
         */
        fun toolCallsToRawJson(toolCalls: List<LlmClient.LlmToolCall>): String {
            val arr = JSONArray()
            for (tc in toolCalls) {
                arr.put(
                    JSONObject()
                        .put("function", JSONObject().put("name", tc.name).put("arguments", tc.input))
                )
            }
            return arr.toString()
        }
    }
}

/**
 * Возвращает фиксированный текст-заглушку, когда языковой дрейф не удалось устранить
 * повторными попытками — вместо испорченного текста с иероглифами.
 */
internal const val LANGUAGE_DRIFT_FALLBACK_TEXT =
    "Не знаю. Ответ модели содержал недопустимые символы (языковой дрейф на другой алфавит), " +
        "не удалось получить корректный ответ на русском. Попробуйте переформулировать вопрос."

/**
 * Генерирует ответ локальной модели с защитой от языкового дрейфа: если в ответе
 * обнаружены CJK-символы, делает до [MAX_LANGUAGE_DRIFT_RETRIES] повторных попыток с
 * усиленным напоминанием и понижающейся температурой. Если дрейф не устранён ни одной
 * попыткой — возвращает [LANGUAGE_DRIFT_FALLBACK_TEXT] вместо испорченного текста.
 *
 * @param modelName   имя локальной модели Ollama
 * @param prompt      текст запроса к модели
 * @param system      системный промпт этапа (опционально) — комбинируется с языковым guard
 * @param temperature температура генерации первой попытки (повторы — ниже, см. реализацию)
 * @param numPredict  потолок токенов генерации (см. [OllamaClient.generate]), `null` — дефолт Ollama
 * @param numCtx      размер окна контекста (см. [OllamaClient.generate]), `null` — дефолт Ollama
 * @return результат генерации (после повторов, если они потребовались)
 */
internal fun generateGuarded(
    modelName: String, prompt: String, system: String? = null, temperature: Double = 0.3,
    numPredict: Int? = null, numCtx: Int? = null
): LocalGenResult {
    val effectiveSystem = if (system.isNullOrBlank()) RUSSIAN_LANGUAGE_GUARD else "$system\n\n$RUSSIAN_LANGUAGE_GUARD"
    val client = OllamaClient(modelName)
    val retryPrompt = "$prompt\n\n(!) Твой предыдущий ответ ошибочно содержал китайские/японские/" +
        "корейские символы. Ответь заново СТРОГО на русском языке, полностью без иероглифов."

    var result = client.generate(prompt, system = effectiveSystem, temperature = temperature, numPredict = numPredict, numCtx = numCtx)
    var attempt = 0
    while (result.error == null && CJK_REGEX.containsMatchIn(result.text) && attempt < MAX_LANGUAGE_DRIFT_RETRIES) {
        attempt++
        val retryTemp = (maxOf(0.0, temperature - attempt * 0.1) * 100).roundToInt() / 100.0
        System.err.println(
            "⚠ $modelName: языковой дрейф на CJK — повторная попытка $attempt/$MAX_LANGUAGE_DRIFT_RETRIES " +
                "(temperature=$retryTemp)..."
        )
        result = client.generate(retryPrompt, system = effectiveSystem, temperature = retryTemp, numPredict = numPredict, numCtx = numCtx)
    }

    if (result.error == null && CJK_REGEX.containsMatchIn(result.text)) {
        System.err.println(
            "⚠ $modelName: дрейф не устранён после $MAX_LANGUAGE_DRIFT_RETRIES повторов — " +
                "возвращаю явный отказ вместо испорченного ответа"
        )
        return result.copy(text = LANGUAGE_DRIFT_FALLBACK_TEXT)
    }
    return result
}
