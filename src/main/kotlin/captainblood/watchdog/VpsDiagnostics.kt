package captainblood.watchdog

import java.util.concurrent.TimeUnit

/**
 * Собирает локальный диагностический контекст на VPS при сбое `/health` — без SSH,
 * все команды выполняются в том же процессе, что и сам watchdog (запускается
 * systemd-таймером на той же машине, где живёт сервис).
 *
 * @return человекочитаемый текст с результатами `uptime`, `systemctl status` для юнитов
 *         `ollama`/`captainblood` и списком слушающих портов; никогда не содержит логин/
 *         пароль (в самих командах их физически нет — тот же принцип, что и в
 *         `docs/VPS_Restart_CapitanBlood.md` про секреты в runbook'ах)
 */
fun collectDiagnostics(): String {
    val sections = listOf(
        "uptime" to runCommand("uptime"),
        "systemctl status ollama" to runCommand("systemctl", "status", "ollama", "--no-pager"),
        "systemctl status captainblood" to runCommand("systemctl", "status", "captainblood", "--no-pager"),
        "слушающие порты" to runCommand("ss", "-tlnp")
    )
    return sections.joinToString("\n\n") { (label, output) -> "=== $label ===\n$output" }
}

private fun runCommand(vararg command: String, timeoutSeconds: Long = 5): String = try {
    val process = ProcessBuilder(*command)
        .redirectErrorStream(true)
        .start()
    val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        "(команда не завершилась за ${timeoutSeconds}с — прервана)"
    } else {
        process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim().ifBlank { "(пусто)" }
    }
} catch (e: Exception) {
    "(ошибка запуска: ${e.message})"
}
