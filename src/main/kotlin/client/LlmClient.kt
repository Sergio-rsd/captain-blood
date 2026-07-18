package client

import agent.mcp.McpTool
import core.ConfigKeys
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Properties

/**
 * Централизованный клиент для работы с LLM через роутер.
 */
object LlmClient {

    /**
     * Модели LLM с их настройками.
     */
    data class LlmModel(
        val displayName: String,
        val apiName: String,
        val choice: String,
        val parametersCount: Long = 0,
        val inputPricePerMillion: Double = 0.0,
        val outputPricePerMillion: Double = 0.0,
        val defaultTemperature: Double = 0.7
    )

    val AVAILABLE_MODELS = listOf(
        LlmModel("anthropic/claude-4.6-sonnet ФЛАГМАН ДОРОГО", "anthropic/claude-sonnet-4.6", "1",
            parametersCount = 0, // Anthropic не раскрывает
            inputPricePerMillion = 280.0, outputPricePerMillion = 1402.0, defaultTemperature = 1.0),
        LlmModel("deepseek/deepseek-v4-pro ФЛАГМАН", "deepseek/deepseek-v4-pro", "2",
            parametersCount = 685_000_000_000L, // ~685 млрд (MoE, активных ~37 млрд)
            inputPricePerMillion = 40.0, outputPricePerMillion = 81.0, defaultTemperature = 0.7),
        LlmModel("openai/gpt-4o-mini СЛАБАЯ", "openai/gpt-4o-mini", "3",
            parametersCount = 8_000_000_000L, // OpenAI не раскрывает
            inputPricePerMillion = 14.0, outputPricePerMillion = 57.0, defaultTemperature = 1.0),
        LlmModel("qwen/qwen3.6-flash НЕ Рассуждает", "qwen/qwen3.6-flash", "4",
            parametersCount = 8_000_000_000L, // точное количество не объявлено
            inputPricePerMillion = 17.0, outputPricePerMillion = 107.0, defaultTemperature = 0.7),
        LlmModel("z-ai/glm-5.2 НОВАЯ", "z-ai/glm-5.2", "5",
            parametersCount = 0, // Z.ai не раскрывает
            inputPricePerMillion = 42.0, outputPricePerMillion = 134.0, defaultTemperature = 1.0),
        LlmModel("qwen/qwen3.5-flash-02-23", "qwen/qwen3.5-flash-02-23", "6",
            parametersCount = 0,
            inputPricePerMillion = 6.0, outputPricePerMillion = 26.0, defaultTemperature = 1.0),
        LlmModel("anthropic/claude-3.5-haiku СРЕДНЯЯ", "anthropic/claude-3.5-haiku", "7",
            parametersCount = 0, // Anthropic не раскрывает
            inputPricePerMillion = 76.0, outputPricePerMillion = 382.0, defaultTemperature = 1.0),
        LlmModel("deepseek/deepseek-v4-flash РАБОЧАЯ", "deepseek/deepseek-v4-flash", "8",
            parametersCount = 284_000_000_000L,
            inputPricePerMillion = 9.0, outputPricePerMillion = 18.0, defaultTemperature = 1.0),
        LlmModel("google/gemini-3.1-pro-preview ДОРОГО", "google/gemini-3.1-pro-preview", "9",
            parametersCount = 0, // google не раскрывает
            inputPricePerMillion = 191.0, outputPricePerMillion = 1146.0, defaultTemperature = 1.0),
        LlmModel("qwen/qwen3.7-max", "qwen/qwen3.7-max", "10",
            parametersCount = 0, // Qwen не раскрывает
            inputPricePerMillion = 119.0, outputPricePerMillion = 358.0, defaultTemperature = 1.0)
    )

    /**
     * Структура полного ответа от LLM со всей метаинформацией.
     */
    data class LlmResponse(
        val model: LlmModel,
        val content: String,
        val elapsedMs: Long,
        val inputTokens: Int,
        val outputTokens: Int,
        val temperature: Double,
        val finishReason: String?,
        val explanation: String,
        val totalCostRub: Double,
        val timestamp: Long = System.currentTimeMillis(),
        val temperatureSetByUser: Boolean = false,
        val toolCalls: List<LlmToolCall> = emptyList()
    )

    /**
     * Единица сообщения в диалоге (для передачи истории).
     *
     * @param role         роль отправителя ("user", "assistant", "tool")
     * @param content      текст сообщения (пустая строка для assistant с tool_calls)
     * @param toolCallId   идентификатор вызова инструмента (только для role="tool")
     * @param rawToolCalls сериализованный JSON-массив tool_calls (только для assistant-ответов с инструментами)
     */
    data class ChatMessage(
        val role: String,
        val content: String,
        val toolCallId: String? = null,
        val rawToolCalls: String? = null
    )

    /**
     * Запрос на вызов инструмента из ответа LLM (OpenAI-совместимый формат).
     *
     * @param id    идентификатор вызова (нужен для tool_result в следующем сообщении)
     * @param name  имя инструмента
     * @param input аргументы вызова
     */
    data class LlmToolCall(
        val id: String,
        val name: String,
        val input: JSONObject
    )

    /**
     * Преобразует display name в API name.
     */
    fun mapModelName(displayName: String): String {
        return AVAILABLE_MODELS.find { it.displayName == displayName }?.apiName
            ?: throw IllegalArgumentException("Неизвестная LLM: $displayName")
    }

    /**
     * Создает тело запроса для chat completions API.
     *
     * @param model название модели
     * @param systemMessage системное сообщение (опционально)
     * @param userMessage пользовательское сообщение
     * @param maxTokens максимальное количество токенов
     * @param temperature температура генерации (0.0 - 2.0)
     * @return JSON-строка тела запроса
     */
    fun buildChatRequestBody(
        model: String,
        systemMessage: String?,
        userMessage: String,
        maxTokens: Int? = null,
        temperature: Double? = null
    ): String {
        val messages = JSONArray()

        // Всегда добавляем требование русского языка как system message
        val effectiveSystemMessage = if (!systemMessage.isNullOrBlank()) {
            "${ConfigKeys.RESPONSE_LANGUAGE}\n$systemMessage"
        } else {
            ConfigKeys.RESPONSE_LANGUAGE
        }
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", effectiveSystemMessage)
        )

        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", userMessage)
        )

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)

        if (maxTokens != null) {
            body.put("max_tokens", maxTokens)
        }

        if (temperature != null) {
            body.put("temperature", temperature)
        }

        return body.toString()
    }

    /**
     * Создает тело запроса для chat completions API с передачей истории диалога.
     *
     * @param model          название модели
     * @param historyMessages список предыдущих сообщений диалога (user/assistant)
     * @param maxTokens      максимальное количество токенов
     * @param temperature    температура генерации (0.0 - 2.0)
     * @return JSON-строка тела запроса
     */
    fun buildChatRequestBody(
        model: String,
        historyMessages: List<ChatMessage>,
        maxTokens: Int? = null,
        temperature: Double? = null,
        systemMessage: String? = null
    ): String {
        val messages = JSONArray()

        // Всегда добавляем требование русского языка как system message
        // Если передан [systemMessage] (от PromptBuilder) — добавляем его после базового
        val effectiveSystemMessage = if (!systemMessage.isNullOrBlank()) {
            "${ConfigKeys.RESPONSE_LANGUAGE}\n$systemMessage"
        } else {
            ConfigKeys.RESPONSE_LANGUAGE
        }
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", effectiveSystemMessage)
        )

        // Добавляем все сообщения из истории
        for (msg in historyMessages) {
            messages.put(
                JSONObject()
                    .put("role", msg.role)
                    .put("content", msg.content)
            )
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)

        if (maxTokens != null) {
            body.put("max_tokens", maxTokens)
        }

        if (temperature != null) {
            body.put("temperature", temperature)
        }

        return body.toString()
    }

    /**
     * Создает HTTP запрос к роутеру.
     *
     * @param baseUrl базовый URL роутера
     * @param apiKey API ключ
     * @param requestBody тело запроса
     * @return готовый HTTP запрос
     */
    fun buildHttpRequest(baseUrl: String, apiKey: String, requestBody: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()
    }

    /**
     * Извлекает текст ответа из JSON-ответа роутера.
     *
     * @param responseBody JSON-строка ответа от роутера
     * @return текст ответа от LLM
     */
    fun extractContent(responseBody: String): String {
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices")

        val message = choices
            ?.optJSONObject(0)
            ?.optJSONObject("message")

        val content = message?.optString("content", null)
        val reasoning = message?.optString("reasoning", null)

        // Приоритет: content (основной ответ), reasoning (только если content пустой)
        // content должен содержать JSON для экстрактора, reasoning — рассуждения модели
        // Если content содержит JSON (начинается с '{'), используем его
        // Если reasoning содержит JSON (есть '{'), извлекаем JSON-часть из reasoning
        val result = when {
            !content.isNullOrBlank() && content.trim().startsWith('{') -> content
            !content.isNullOrBlank() -> content
            !reasoning.isNullOrBlank() && reasoning.contains('{') -> {
                // Извлекаем JSON из reasoning: всё от первой '{' до конца
                val jsonStart = reasoning.indexOf('{')
                reasoning.substring(jsonStart)
            }
            !reasoning.isNullOrBlank() -> reasoning
            else -> throw RuntimeException("Ответ не содержит ни choices[0].message.content, ни choices[0].message.reasoning.")
        }

        return result
    }

    /**
     * Извлекает usage и finish_reason из JSON-ответа роутера.
     *
     * @param responseBody JSON-строка ответа от роутера
     * @return Triple с prompt_tokens, completion_tokens, finish_reason
     */
    private fun extractUsage(responseBody: String): Triple<Int, Int, String?> {
        val json = JSONObject(responseBody)
        val usage = json.optJSONObject("usage")
        val promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0
        val completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
        val choices = json.optJSONArray("choices")
        val finishReason = choices
            ?.optJSONObject(0)
            ?.optString("finish_reason", null)
        return Triple(promptTokens, completionTokens, finishReason)
    }

    /**
     * Проверяет статус ответа, сохраняет отладочный JSON и возвращает тело ответа.
     */
    private fun validateAndDebugResponse(response: HttpResponse<String>, reason: String = "no_context_trimming"): String {
        if (response.statusCode() != 200) {
            throw RuntimeException("Ошибка роутера: ${response.statusCode()} - ${response.body()}")
        }
        val responseBody = response.body()
        // Сохраняем полный JSON-ответ API в файл для отладки с полем reason
        try {
            val debugJson = JSONObject(responseBody)
            debugJson.put("reason", reason)
            java.io.File("debug_api_reason.json").writeText(debugJson.toString(2))
            // Ведущий перевод строки — иначе в вызывающих коде с тикающим индикатором
            // прогресса (\r-перерисовка строки, см. videototext.ProgressTicker) вывод
            // наезжает на незавершённую строку тикера.
            println("\n[DEBUG] Ответ сохранён в debug_api_reason.json (reason: $reason)")
        } catch (_: Exception) {
            // игнорируем ошибки записи
        }
        return responseBody
    }

    /**
     * Отправляет запрос к LLM через роутер и возвращает полную информацию об ответе.
     *
     * @param model модель LLM из AVAILABLE_MODELS
     * @param userMessage пользовательское сообщение
     * @param systemMessage системное сообщение (опционально)
     * @param maxTokens максимальное количество токенов
     * @param userTemperature температура от пользователя (null — использовать model.defaultTemperature)
     * @param config объект конфигурации
     * @return полный ответ LlmResponse со всей метаинформацией
     */
    fun sendRequestFull(
        model: LlmModel,
        userMessage: String,
        systemMessage: String? = null,
        maxTokens: Int? = null,
        userTemperature: Double? = null,
        config: Properties,
        reason: String = "no_context_trimming"
    ): LlmResponse {
        val apiKey = config.getProperty(ConfigKeys.API_KEY)
            ?: error("${ConfigKeys.API_KEY} не задан в ${ConfigKeys.CONFIG_FILE}")
        val baseUrl = config.getProperty(ConfigKeys.BASE_URL)
            ?: error("${ConfigKeys.BASE_URL} не задан в ${ConfigKeys.CONFIG_FILE}")

        val temperature = userTemperature ?: model.defaultTemperature
        val requestBody = buildChatRequestBody(model.apiName, systemMessage, userMessage, maxTokens, temperature)
        val request = buildHttpRequest(baseUrl, apiKey, requestBody)

        val client = HttpClient.newHttpClient()
        val startTime = System.currentTimeMillis()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val elapsedMs = System.currentTimeMillis() - startTime

        val responseBody = validateAndDebugResponse(response, reason)

        val content = extractContent(responseBody)
        val (inputTokens, outputTokens, finishReason) = extractUsage(responseBody)

        // Расчёт стоимости в рублях
        val totalCostRub = (inputTokens / 1_000_000.0) * model.inputPricePerMillion +
                (outputTokens / 1_000_000.0) * model.outputPricePerMillion

        // Автоматическое объяснение
        val tempSource = if (userTemperature != null) "пользовательская" else "по умолчанию модели"
        val tempDesc = when {
            temperature < 0.3 -> "детерминированный"
            temperature < 0.7 -> "сбалансированный"
            temperature < 1.0 -> "креативный"
            else -> "очень креативный"
        }
        val finishDesc = when (finishReason) {
            "stop" -> "завершён естественно"
            "length" -> "обрезан по длине"
            "content_filter" -> "отфильтрован по содержанию"
            else -> "завершён (${finishReason ?: "неизвестно"})"
        }
        val tokenDesc = when {
            outputTokens < 50 -> "краткий"
            outputTokens < 200 -> "средний"
            else -> "развёрнутый"
        }
        val modelClass = when {
            model.displayName.contains("ФЛАГМАН") -> " (ФЛАГМАН)"
            model.displayName.contains("СЛАБАЯ") -> " (СЛАБАЯ)"
            model.displayName.contains("СРЕДНЯЯ") -> " (СРЕДНЯЯ)"
            else -> ""
        }

        val explanation = "Модель ${model.displayName}${modelClass} сгенерировала $tokenDesc ответ " +
                "за $outputTokens токенов (вход: $inputTokens). " +
                "Температура $temperature ($tempSource, $tempDesc). " +
                "Ответ $finishDesc (finish_reason=${finishReason ?: "неизвестно"})."

        return LlmResponse(
            model = model,
            content = content,
            elapsedMs = elapsedMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            temperature = temperature,
            finishReason = finishReason,
            explanation = explanation,
            totalCostRub = totalCostRub,
            temperatureSetByUser = userTemperature != null
        )
    }

    /**
     * Отправляет запрос к LLM с полной историей диалога (контекстом).
     *
     * Используется агентом для продолжения диалога: передаётся вся предыдущая
     * переписка (user/assistant) плюс новое сообщение пользователя.
     *
     * @param model      модель LLM из AVAILABLE_MODELS
     * @param history    список предыдущих сообщений (ChatMessage с ролями user/assistant)
     * @param newMessage новое сообщение пользователя (добавляется в конец истории)
     * @param maxTokens  максимальное количество токенов
     * @param userTemperature температура от пользователя (null — использовать defaultTemperature модели)
     * @param config     объект конфигурации
     * @return полный ответ LlmResponse со всей метаинформацией
     */
    fun sendRequestWithHistory(
        model: LlmModel,
        history: List<ChatMessage>,
        newMessage: String,
        maxTokens: Int? = null,
        userTemperature: Double? = null,
        config: Properties,
        reason: String = "no_context_trimming",
        systemMessage: String? = null
    ): LlmResponse {
        val apiKey = config.getProperty(ConfigKeys.API_KEY)
            ?: error("${ConfigKeys.API_KEY} не задан в ${ConfigKeys.CONFIG_FILE}")
        val baseUrl = config.getProperty(ConfigKeys.BASE_URL)
            ?: error("${ConfigKeys.BASE_URL} не задан в ${ConfigKeys.CONFIG_FILE}")

        val temperature = userTemperature ?: model.defaultTemperature

        // Собираем полный список сообщений: история + новый вопрос
        val allMessages = history.toMutableList()
        allMessages.add(ChatMessage("user", newMessage))

        val requestBody = buildChatRequestBody(model.apiName, allMessages, maxTokens, temperature, systemMessage)
        val request = buildHttpRequest(baseUrl, apiKey, requestBody)

        val client = HttpClient.newHttpClient()
        val startTime = System.currentTimeMillis()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val elapsedMs = System.currentTimeMillis() - startTime

        val responseBody = validateAndDebugResponse(response, reason)

        val content = extractContent(responseBody)
        val (inputTokens, outputTokens, finishReason) = extractUsage(responseBody)

        // Расчёт стоимости в рублях
        val totalCostRub = (inputTokens / 1_000_000.0) * model.inputPricePerMillion +
                (outputTokens / 1_000_000.0) * model.outputPricePerMillion

        // Автоматическое объяснение
        val tempSource = if (userTemperature != null) "пользовательская" else "по умолчанию модели"
        val tempDesc = when {
            temperature < 0.3 -> "детерминированный"
            temperature < 0.7 -> "сбалансированный"
            temperature < 1.0 -> "креативный"
            else -> "очень креативный"
        }
        val finishDesc = when (finishReason) {
            "stop" -> "завершён естественно"
            "length" -> "обрезан по длине"
            "content_filter" -> "отфильтрован по содержанию"
            else -> "завершён (${finishReason ?: "неизвестно"})"
        }
        val tokenDesc = when {
            outputTokens < 50 -> "краткий"
            outputTokens < 200 -> "средний"
            else -> "развёрнутый"
        }
        val modelClass = when {
            model.displayName.contains("ФЛАГМАН") -> " (ФЛАГМАН)"
            model.displayName.contains("СЛАБАЯ") -> " (СЛАБАЯ)"
            model.displayName.contains("СРЕДНЯЯ") -> " (СРЕДНЯЯ)"
            else -> ""
        }

        val explanation = "Модель ${model.displayName}${modelClass} сгенерировала $tokenDesc ответ " +
                "за $outputTokens токенов (вход: $inputTokens, история: ${history.size} сообщений). " +
                "Температура $temperature ($tempSource, $tempDesc). " +
                "Ответ $finishDesc (finish_reason=${finishReason ?: "неизвестно"})."

        return LlmResponse(
            model = model,
            content = content,
            elapsedMs = elapsedMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            temperature = temperature,
            finishReason = finishReason,
            explanation = explanation,
            totalCostRub = totalCostRub,
            temperatureSetByUser = userTemperature != null
        )
    }

    /**
     * Отправляет запрос к LLM через роутер.
     *
     * @param llmDisplayName отображаемое имя LLM
     * @param userMessage пользовательское сообщение
     * @param systemMessage системное сообщение (опционально)
     * @param maxTokens максимальное количество токенов
     * @param temperature температура генерации (опционально)
     * @param config объект конфигурации
     * @return ответ от LLM
     */
    fun sendRequest(
        llmDisplayName: String,
        userMessage: String,
        systemMessage: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        config: Properties
    ): String {
        val apiKey = config.getProperty(ConfigKeys.API_KEY)
            ?: error("${ConfigKeys.API_KEY} не задан в ${ConfigKeys.CONFIG_FILE}")
        val baseUrl = config.getProperty(ConfigKeys.BASE_URL)
            ?: error("${ConfigKeys.BASE_URL} не задан в ${ConfigKeys.CONFIG_FILE}")

        val apiModelName = mapModelName(llmDisplayName)
        val requestBody = buildChatRequestBody(apiModelName, systemMessage, userMessage, maxTokens, temperature)
        val request = buildHttpRequest(baseUrl, apiKey, requestBody)

        val client = HttpClient.newHttpClient()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        val responseBody = validateAndDebugResponse(response)

        return extractContent(responseBody)
    }

    /**
     * Отправляет запрос с обработкой ошибок.
     *
     * @return ответ от LLM или сообщение об ошибке
     */
    fun sendRequestSafe(
        llmDisplayName: String,
        userMessage: String,
        systemMessage: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        config: Properties
    ): String {
        return try {
            sendRequest(llmDisplayName, userMessage, systemMessage, maxTokens, temperature, config)
        } catch (e: Exception) {
            "Ошибка при отправке запроса: ${e.message}"
        }
    }

    // =====================================================================
    // tool_use — поддержка вызова инструментов
    // =====================================================================

    /**
     * Создает тело запроса с массивом инструментов (OpenAI-совместимый формат).
     *
     * Поддерживает специальные роли в истории:
     * - role="tool" → tool_result с полем tool_call_id
     * - rawToolCalls != null → assistant-сообщение с массивом tool_calls
     *
     * @param model           название модели
     * @param historyMessages история диалога (включая tool_calls и tool_result)
     * @param tools           список инструментов; пустой список — не добавляет поле tools
     * @param maxTokens       максимальное количество токенов
     * @param temperature     температура генерации
     * @param systemMessage   системное сообщение
     * @return JSON-строка тела запроса
     */
    fun buildChatRequestBodyWithTools(
        model: String,
        historyMessages: List<ChatMessage>,
        tools: List<McpTool>,
        maxTokens: Int? = null,
        temperature: Double? = null,
        systemMessage: String? = null
    ): String {
        val messages = JSONArray()

        val effectiveSystem = if (!systemMessage.isNullOrBlank()) {
            "${ConfigKeys.RESPONSE_LANGUAGE}\n$systemMessage"
        } else {
            ConfigKeys.RESPONSE_LANGUAGE
        }
        messages.put(JSONObject().put("role", "system").put("content", effectiveSystem))

        for (msg in historyMessages) {
            val obj = when {
                msg.role == "tool" -> JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", msg.toolCallId ?: "")
                    .put("content", msg.content)
                msg.rawToolCalls != null -> JSONObject()
                    .put("role", "assistant")
                    .put("tool_calls", JSONArray(msg.rawToolCalls))
                else -> JSONObject()
                    .put("role", msg.role)
                    .put("content", msg.content)
            }
            messages.put(obj)
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)

        if (tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            for (tool in tools) {
                toolsArr.put(JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("parameters", tool.inputSchema)))
            }
            body.put("tools", toolsArr)
            body.put("tool_choice", "auto")
        }

        if (maxTokens != null) body.put("max_tokens", maxTokens)
        if (temperature != null) body.put("temperature", temperature)

        return body.toString()
    }

    /**
     * Извлекает список tool_calls из JSON-ответа LLM (OpenAI-формат).
     *
     * @param responseBody JSON-строка ответа от роутера
     * @return список запрошенных вызовов инструментов (пустой если stop/length)
     */
    fun extractToolCalls(responseBody: String): List<LlmToolCall> {
        val json = JSONObject(responseBody)
        val message = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message") ?: return emptyList()
        val toolCallsArr = message.optJSONArray("tool_calls") ?: return emptyList()
        return (0 until toolCallsArr.length()).mapNotNull { i ->
            val tc = toolCallsArr.optJSONObject(i) ?: return@mapNotNull null
            val fn = tc.optJSONObject("function") ?: return@mapNotNull null
            val argsStr = fn.optString("arguments", "{}")
            val input = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
            LlmToolCall(
                id = tc.optString("id"),
                name = fn.optString("name"),
                input = input
            )
        }
    }

    /**
     * Отправляет запрос к LLM с поддержкой tool_use (OpenAI-совместимый формат).
     *
     * При finish_reason="tool_calls" возвращает LlmResponse с непустым [LlmResponse.toolCalls].
     * При пустом списке tools или finish_reason="stop" возвращает обычный текстовый ответ.
     *
     * Для добавления результатов в историю:
     * - assistant-сообщение с rawToolCalls = toolCallsToRawJson(response.toolCalls)
     * - tool-сообщение с role="tool", toolCallId=id, content=результат
     *
     * @param model      модель LLM
     * @param history    история диалога (может содержать tool и assistant+rawToolCalls)
     * @param newMessage новый вопрос пользователя (пустая строка — продолжить без нового вопроса)
     * @param tools      список инструментов (пустой — запрос без инструментов)
     * @param config     конфигурация
     * @param systemMessage системное сообщение
     * @return полный ответ, включая toolCalls если LLM запросила инструменты
     */
    fun sendRequestWithTools(
        model: LlmModel,
        history: List<ChatMessage>,
        newMessage: String,
        tools: List<McpTool>,
        config: Properties,
        systemMessage: String? = null
    ): LlmResponse {
        val apiKey = config.getProperty(ConfigKeys.API_KEY)
            ?: error("${ConfigKeys.API_KEY} не задан в ${ConfigKeys.CONFIG_FILE}")
        val baseUrl = config.getProperty(ConfigKeys.BASE_URL)
            ?: error("${ConfigKeys.BASE_URL} не задан в ${ConfigKeys.CONFIG_FILE}")

        val temperature = model.defaultTemperature
        val allMessages = history.toMutableList()
        if (newMessage.isNotBlank()) allMessages.add(ChatMessage("user", newMessage))

        val requestBody = buildChatRequestBodyWithTools(
            model.apiName, allMessages, tools, null, temperature, systemMessage
        )
        val request = buildHttpRequest(baseUrl, apiKey, requestBody)

        val client = HttpClient.newHttpClient()
        val startTime = System.currentTimeMillis()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val elapsedMs = System.currentTimeMillis() - startTime

        val responseBody = validateAndDebugResponse(response, "tool_use")
        val (inputTokens, outputTokens, finishReason) = extractUsage(responseBody)
        val toolCalls = extractToolCalls(responseBody)

        val content = if (finishReason == "tool_calls") "" else extractContent(responseBody)

        val totalCostRub = (inputTokens / 1_000_000.0) * model.inputPricePerMillion +
                (outputTokens / 1_000_000.0) * model.outputPricePerMillion
        val finishDesc = when (finishReason) {
            "stop"       -> "завершён естественно"
            "tool_calls" -> "запросил инструменты (${toolCalls.size})"
            "length"     -> "обрезан по длине"
            else         -> "завершён (${finishReason ?: "неизвестно"})"
        }
        val explanation = "Модель ${model.displayName} сгенерировала ответ " +
                "за $outputTokens токенов (вход: $inputTokens). " +
                "Температура $temperature. Ответ $finishDesc."

        return LlmResponse(
            model = model,
            content = content,
            elapsedMs = elapsedMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            temperature = temperature,
            finishReason = finishReason,
            explanation = explanation,
            totalCostRub = totalCostRub,
            toolCalls = toolCalls
        )
    }

    // =====================================================================
    // Подсчёт токенов и работа с контекстом
    // =====================================================================

    /**
     * Приблизительно оценивает количество токенов в тексте.
     * Использует [ConfigKeys.CHARS_PER_TOKEN] для грубой оценки.
     *
     * @param text текст для подсчёта
     * @return оценочное количество токенов
     */
    fun countTokens(text: String): Int {
        return (text.length + ConfigKeys.CHARS_PER_TOKEN - 1) / ConfigKeys.CHARS_PER_TOKEN
    }

    /**
     * Считает суммарное количество токенов в истории диалога.
     *
     * @param history список сообщений [ChatMessage]
     * @return оценочное количество токенов всей истории
     */
    fun countHistoryTokens(history: List<ChatMessage>): Int {
        return history.sumOf { countTokens(it.content) }
    }

    /**
     * Суммаризирует историю диалога с помощью той же LLM-модели.
     *
     * Отправляет модели промпт с просьбой кратко суммаризировать диалог,
     * сохраняя ключевую информацию (имена, факты, контекст).
     *
     * @param history список сообщений для суммаризации
     * @param model   модель LLM, которая будет использована для суммаризации
     * @param config  конфигурация (API_KEY, BASE_URL)
     * @return строка с кратким содержанием диалога
     */
    fun summarizeHistory(
        history: List<ChatMessage>,
        model: LlmModel,
        config: Properties
    ): String {
        if (history.isEmpty()) return ""

        // Собираем текст диалога
        val dialogueText = history.joinToString("\n") { msg ->
            "${if (msg.role == "user") "Пользователь" else "Ассистент"}: ${msg.content}"
        }

        val summaryPrompt = buildString {
            append("Суммаризируй следующий диалог кратко (3-5 предложений), ")
            append("сохраняя ключевую информацию: имена, факты, суть запросов и ответов.\n\n")
            append("Диалог:\n")
            append(dialogueText)
        }

        return try {
            val response = sendRequestFull(
                model = model,
                userMessage = summaryPrompt,
                systemMessage = "Ты — ассистент для суммаризации диалогов. Отвечай кратко на русском языке.",
                maxTokens = 500,
                userTemperature = 0.3, // низкая температура для стабильной суммаризации
                config = config,
                reason = "context_summarization"
            )
            "[Суммаризация предыдущего диалога]: ${response.content}"
        } catch (e: Exception) {
            println("[WARN] Не удалось суммаризировать историю: ${e.message}")
            "[Суммаризация не удалась: ${e.message}]"
        }
    }

    /**
     * Генерирует моковый ответ для тестирования без API ключей.
     */
    fun generateMockResponse(llmDisplayName: String, blockName: String, query: String): String {
        return """
            |=== $blockName ===
            |LLM: $llmDisplayName
            |Задача: $query
            |
            |Это моковый ответ для демонстрации работы приложения.
            |Для получения реальных ответов создайте файл resources/${ConfigKeys.CONFIG_FILE}
            |с ключами ${ConfigKeys.API_KEY} и ${ConfigKeys.BASE_URL}.
        """.trimMargin()
    }
}
