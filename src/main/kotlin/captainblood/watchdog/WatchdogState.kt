package captainblood.watchdog

import org.json.JSONObject
import java.io.File

private const val CIRCUIT_BREAKER_THRESHOLD = 3

/**
 * Персистентное состояние watchdog'а между запусками (systemd-таймер вызывает
 * `WatchdogMain` заново каждые несколько минут, процесс не живёт постоянно) — считает
 * подряд идущие неудачные попытки восстановления, чтобы не уйти в рестарт-петлю при
 * невосстановимом сбое.
 *
 * @param path путь к JSON-файлу состояния
 */
class WatchdogState(private val path: String) {
    private var consecutiveFailures: Int = 0

    init {
        val file = File(path)
        if (file.exists()) {
            consecutiveFailures = try {
                JSONObject(file.readText()).optInt("consecutiveFailures", 0)
            } catch (_: Exception) {
                0
            }
        }
    }

    /**
     * Проверяет, нужно ли отключить автоприменение действий восстановления.
     *
     * @return true после [CIRCUIT_BREAKER_THRESHOLD] и более подряд идущих неудач —
     *         дальше только диагностика в лог, без реальных команд, до ручного вмешательства
     */
    fun circuitBreakerTripped(): Boolean = consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD

    /** Сбрасывает счётчик после подтверждённо здорового `/health` или успешного восстановления. */
    fun recordSuccess() {
        consecutiveFailures = 0
        persist()
    }

    /** Увеличивает счётчик после неудачной попытки восстановления. */
    fun recordFailure() {
        consecutiveFailures++
        persist()
    }

    private fun persist() {
        File(path).writeText(JSONObject().put("consecutiveFailures", consecutiveFailures).toString())
    }
}
