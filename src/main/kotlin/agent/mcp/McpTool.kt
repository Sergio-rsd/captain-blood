package agent.mcp

import org.json.JSONObject

/**
 * Описание одного инструмента MCP-сервера.
 *
 * @param name уникальное имя инструмента
 * @param description краткое описание назначения
 * @param inputSchema JSON Schema входных параметров
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

/**
 * Результат инициализации MCP-соединения.
 *
 * @param serverName имя сервера из поля serverInfo.name
 * @param serverVersion версия сервера
 * @param protocolVersion версия протокола MCP
 */
data class McpInitResult(
    val serverName: String,
    val serverVersion: String,
    val protocolVersion: String
)

/**
 * Результат вызова инструмента MCP-сервера.
 *
 * @param content текстовый результат (обычно JSON или описание)
 * @param isError true если сервер вернул ошибку
 */
data class McpCallResult(
    val content: String,
    val isError: Boolean = false
)
