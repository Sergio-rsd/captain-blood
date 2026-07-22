package captainblood.watchdog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecoveryActionTest {

    @Test
    fun `fromLlmAnswer распознаёт точное совпадение имени`() {
        assertEquals(RecoveryAction.RESTART_OLLAMA, RecoveryAction.fromLlmAnswer("RESTART_OLLAMA"))
        assertEquals(RecoveryAction.RESTART_BOTH, RecoveryAction.fromLlmAnswer("RESTART_BOTH"))
    }

    @Test
    fun `fromLlmAnswer возвращает MANUAL_REQUIRED при опечатке или мусоре`() {
        assertEquals(RecoveryAction.MANUAL_REQUIRED, RecoveryAction.fromLlmAnswer("restart_ollama"))
        assertEquals(RecoveryAction.MANUAL_REQUIRED, RecoveryAction.fromLlmAnswer("что-то не то"))
        assertEquals(RecoveryAction.MANUAL_REQUIRED, RecoveryAction.fromLlmAnswer(null))
        assertEquals(RecoveryAction.MANUAL_REQUIRED, RecoveryAction.fromLlmAnswer(""))
    }

    @Test
    fun `command возвращает провалидированную shell-команду для каждого действия`() {
        assertEquals("systemctl restart ollama", RecoveryAction.RESTART_OLLAMA.command())
        assertEquals("systemctl restart captainblood", RecoveryAction.RESTART_CAPTAINBLOOD_SERVICE.command())
        assertEquals("systemctl restart ollama && systemctl restart captainblood", RecoveryAction.RESTART_BOTH.command())
    }

    @Test
    fun `command для MANUAL_REQUIRED возвращает null — ничего не выполняется`() {
        assertNull(RecoveryAction.MANUAL_REQUIRED.command())
    }
}
