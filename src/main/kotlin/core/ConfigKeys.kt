package core

/**
 * Централизованные константы конфигурации для всего проекта.
 */
object ConfigKeys {
    // Имена файлов
    const val CONFIG_FILE = "config.properties"
    
    // Ключи конфигурации
    const val API_KEY = "API_KEY"
    const val BASE_URL = "BASE_URL"

    // Приватный LLM-сервис на VPS
    const val PRIVATE_LLM_LOGIN = "PRIVATE_LLM_LOGIN"        // логин для HTTP Basic Auth на /chat
    const val PRIVATE_LLM_PASSWORD = "PRIVATE_LLM_PASSWORD"  // пароль для HTTP Basic Auth на /chat
    const val VPS_HOST = "VPS_HOST"                          // публичный адрес VPS (IP или host:port)
    const val VPS_CLIENT_BASE_URL = "VPS_CLIENT_BASE_URL"    // полный base URL сервиса для клиент-режима (http://<ip>:8080) — отдельно от VPS_HOST (тот в формате user@host, для scp)
    const val VPS_SSH_KEY_PATH = "VPS_SSH_KEY_PATH"          // путь к приватному SSH-ключу для scp-синка базы
    const val VPS_REMOTE_DB_PATH = "VPS_REMOTE_DB_PATH"      // путь к domain_assistant.db на VPS
    const val SHOW_SOURCES_IN_UI = "SHOW_SOURCES_IN_UI"      // "false" — скрыть строку источников в HTML-морде (перед деплоем); по умолчанию true (локальный дебаг)

    // Watchdog с LLM-диагностикой
    const val WATCHDOG_MODEL = "WATCHDOG_MODEL"              // displayName модели из client.LlmClient.AVAILABLE_MODELS для диагностики сбоя
    const val WATCHDOG_HEALTH_URL = "WATCHDOG_HEALTH_URL"    // URL health-эндпоинта сервиса, дефолт http://localhost:8080/health

    // Токены и лимиты
    const val CHARS_PER_TOKEN = 4
    const val TOKEN_ESTIMATE_DIVISOR = 3
    const val MIN_SAFE_TOKENS = 50
    const val MAX_TOKENS_DEFAULT = 4000
    const val MAX_TOKENS_REASONING = 16000
    const val MAX_TOKENS_CLAUDE = 1000
    const val INPUT_CONTEXT_TOKEN_LIMIT = 8000
    
    // Сжатие истории
    const val COMPRESSION_KEEP_LAST_N = 5      // Сколько последних сообщений хранить «как есть»
    const val COMPRESSION_SUMMARY_EVERY_M = 5   // Батч из M старых сообщений → 1 summary
    
    // Стратегии контекста
    const val SLIDING_WINDOW_HARD_KEEP_N = 8    // Sliding Window Hard: только последние N сообщений
    const val STICKY_FACTS_KEEP_N = 8            // Sticky Facts: facts + последние N сообщений
    
    // Форматирование
    const val DEFAULT_COLUMN_WIDTH = 150
    
    // Язык ответа
    const val RESPONSE_LANGUAGE = "Отвечай на русском языке."
}
