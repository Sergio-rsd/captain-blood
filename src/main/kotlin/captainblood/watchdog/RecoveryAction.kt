package captainblood.watchdog

/**
 * Действие восстановления, которое watchdog может применить автоматически.
 *
 * LLM выбирает ОДНО значение из этого enum по собранной диагностике — саму shell-команду
 * пишет не модель, а код (см. [command]): так исключается риск произвольной команды,
 * которую LLM могла бы сформулировать текстом (command injection/абсурдная команда).
 * LLM отвечает за диагноз и выбор ИЗ меню, не за генерацию команды.
 */
enum class RecoveryAction {
    RESTART_OLLAMA,
    RESTART_CAPTAINBLOOD_SERVICE,
    RESTART_BOTH,
    MANUAL_REQUIRED;

    /**
     * Возвращает заранее написанную и провалидированную shell-команду для этого действия.
     *
     * @return команда для `sh -c`, либо `null` для [MANUAL_REQUIRED] (ничего не выполняется)
     */
    fun command(): String? = when (this) {
        RESTART_OLLAMA -> "systemctl restart ollama"
        RESTART_CAPTAINBLOOD_SERVICE -> "systemctl restart captainblood"
        RESTART_BOTH -> "systemctl restart ollama && systemctl restart captainblood"
        MANUAL_REQUIRED -> null
    }

    companion object {
        /**
         * Разбирает ответ LLM в одно из значений enum по точному совпадению имени.
         *
         * @param raw сырое значение поля `action` из JSON-ответа LLM
         * @return найденное значение enum, либо [MANUAL_REQUIRED] при любом несовпадении
         *         (опечатка, посторонний текст, неизвестное значение) — безопасный дефолт
         *         вместо попытки угадать намерение модели
         */
        fun fromLlmAnswer(raw: String?): RecoveryAction =
            entries.find { it.name == raw?.trim() } ?: MANUAL_REQUIRED
    }
}
