package captainblood.watchdog

import client.LlmClient
import core.ConfigKeys
import core.loadConfigOrNullWithMessage
import core.printlnColumn
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

private const val STATE_FILE_PATH = "captainblood-watchdog.state.json"
private const val LOG_FILE_PATH = "captainblood-watchdog.log"
private const val HEALTH_CHECK_TIMEOUT_MS = 5_000
private const val POST_ACTION_RECHECK_DELAY_MS = 15_000L
private const val DEFAULT_HEALTH_URL = "http://localhost:8080/health"
private const val DEFAULT_WATCHDOG_MODEL = "deepseek/deepseek-v4-flash РАБОЧАЯ"

private val DIAGNOSIS_SYSTEM_PROMPT = """
Ты — SRE-ассистент, диагностирующий сбой приватного LLM-чат-сервиса "капитан Блад" на Linux VPS
(управляется systemd). Сервис отвечает на /health; сейчас он не отвечает "ok". Тебе дан
диагностический контекст (uptime, systemctl status двух юнитов, список слушающих портов).
Поставь короткий диагноз (1-2 предложения) и выбери РОВНО ОДНО действие восстановления из
списка ниже — не придумывай своё действие и не пиши shell-команды.

Допустимые значения action:
- RESTART_OLLAMA — Ollama (LLM-движок) не запущена/упала, но сам процесс captainblood жив
- RESTART_CAPTAINBLOOD_SERVICE — сам процесс captainblood не запущен/завис, а Ollama в порядке
- RESTART_BOTH — непонятно, какой из двух компонентов виноват, или оба выглядят нездоровыми
- MANUAL_REQUIRED — проблема не похожа на "процесс не запущен" (диск полон, порт занят
  посторонним процессом, ошибка конфигурации и т.п.) — рестарт не поможет, нужен человек

Ответь СТРОГО в формате JSON, без markdown и пояснений вне JSON:
{"diagnosis": "...", "action": "ONE_OF_THE_FOUR_VALUES_ABOVE"}
""".trimIndent()

/**
 * Разовый запуск watchdog'а — вызывается systemd-таймером каждые несколько минут.
 *
 * Логика: если `/health` отвечает `ok` — тихий выход (циклы без сбоя ничего не стоят,
 * облачная LLM не вызывается). При сбое — собирает локальную диагностику, просит облачную
 * LLM поставить диагноз и выбрать действие из строгого allowlist ([RecoveryAction]),
 * применяет его (кроме `--dry-run` и срабатывания circuit breaker — см. [WatchdogState]),
 * перепроверяет `/health` и пишет строку в лог.
 *
 * @param args поддерживает флаг `--dry-run` — диагностика и выбор действия выполняются как
 *             обычно, но реальная shell-команда не запускается (для локального теста на
 *             машине без systemd/без риска что-то сломать)
 */
fun main(args: Array<String>) {
    val dryRun = args.contains("--dry-run")
    val config = loadConfigOrNullWithMessage()
    if (config == null) {
        printlnColumn("[watchdog] конфигурация не найдена, выхожу")
        return
    }

    val healthUrl = config.getProperty(ConfigKeys.WATCHDOG_HEALTH_URL, DEFAULT_HEALTH_URL)
    val state = WatchdogState(STATE_FILE_PATH)

    if (checkHealth(healthUrl)) {
        state.recordSuccess()
        return
    }

    logLine("/health недоступен ($healthUrl) - начинаю диагностику")

    val circuitBroken = state.circuitBreakerTripped()
    if (circuitBroken) {
        logLine("circuit breaker сработал (3+ неудачных попытки подряд) - только диагностика, без авто-восстановления")
    }

    val diagnosticsText = collectDiagnostics()
    val decision = diagnose(config, diagnosticsText)
    logLine("диагноз: ${decision.diagnosis} | действие: ${decision.action}")

    if (dryRun) {
        logLine("[--dry-run] действие НЕ применяется, только лог")
    } else if (!circuitBroken) {
        val command = decision.action.command()
        if (command != null) {
            val exitCode = runShell(command)
            logLine("применена команда '$command', код выхода $exitCode")
            Thread.sleep(POST_ACTION_RECHECK_DELAY_MS)
        }
    }

    if (dryRun) return

    if (checkHealth(healthUrl)) {
        logLine("/health снова ok")
        state.recordSuccess()
    } else {
        logLine("/health всё ещё недоступен после действия")
        state.recordFailure()
    }
}

private data class Decision(val diagnosis: String, val action: RecoveryAction)

/**
 * Просит облачную LLM поставить диагноз по собранному контексту и выбрать действие.
 *
 * @param config          конфигурация с `API_KEY`/`BASE_URL`/[ConfigKeys.WATCHDOG_MODEL]
 * @param diagnosticsText текст из [collectDiagnostics]
 * @return диагноз и выбранное действие; при любой ошибке вызова/разбора — [RecoveryAction.MANUAL_REQUIRED]
 */
private fun diagnose(config: Properties, diagnosticsText: String): Decision {
    val modelName = config.getProperty(ConfigKeys.WATCHDOG_MODEL) ?: DEFAULT_WATCHDOG_MODEL
    val model = LlmClient.AVAILABLE_MODELS.find { it.displayName == modelName }
        ?: LlmClient.AVAILABLE_MODELS.first { it.displayName == DEFAULT_WATCHDOG_MODEL }
    return try {
        val response = LlmClient.sendRequestWithHistory(
            model = model,
            history = emptyList(),
            newMessage = "Диагностический контекст:\n\n$diagnosticsText",
            config = config,
            reason = "captainblood_watchdog_diagnosis",
            systemMessage = DIAGNOSIS_SYSTEM_PROMPT
        )
        parseDecision(response.content)
    } catch (e: Exception) {
        Decision("вызов LLM не удался: ${e.message}", RecoveryAction.MANUAL_REQUIRED)
    }
}

private fun parseDecision(raw: String): Decision = try {
    val jsonStart = raw.indexOf('{')
    val jsonEnd = raw.lastIndexOf('}')
    val json = JSONObject(raw.substring(jsonStart, jsonEnd + 1))
    Decision(
        diagnosis = json.optString("diagnosis").ifBlank { "(без диагноза)" },
        action = RecoveryAction.fromLlmAnswer(json.optString("action").ifBlank { null })
    )
} catch (e: Exception) {
    Decision("не удалось разобрать ответ LLM: ${raw.take(200)}", RecoveryAction.MANUAL_REQUIRED)
}

private fun checkHealth(url: String): Boolean = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = HEALTH_CHECK_TIMEOUT_MS
    conn.readTimeout = HEALTH_CHECK_TIMEOUT_MS
    val code = conn.responseCode
    conn.disconnect()
    code == 200
} catch (_: Exception) {
    false
}

private fun runShell(command: String): Int {
    val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
    val exitCode = process.waitFor()
    if (output.isNotBlank()) logLine("вывод команды: ${output.trim().take(500)}")
    return exitCode
}

private fun logLine(text: String) {
    val line = "${java.time.LocalDateTime.now()} $text"
    printlnColumn(line)
    try {
        File(LOG_FILE_PATH).appendText(line + System.lineSeparator())
    } catch (_: Exception) {
        // логирование в файл best-effort - отсутствие прав на запись не должно ронять watchdog
    }
}
