# CLAUDE.md — Captain Blood

Файл правил проекта — стек, архитектура, конвенции, паттерны и антипаттерны,
которым должен следовать любой код (человека или ассистента), добавляемый в
этот репозиторий. Проанализирован по реальному коду проекта на момент написания
(2026-07-20, ветка `feature/control-restart`).

## О проекте

Приватный LLM-чат-сервис "капитан Блад" — RAG-эрудит-бот про море/пиратов для
детей 12+, развёрнутый на VPS. Второй компонент — watchdog с LLM-диагностикой,
который при сбое `/health` сам ставит диагноз и чинит сервис, а не просто
перезапускает его по таймеру. Подробности задачи — `README.md`, деплой —
`docs/VPS_Deploy_CaptainBlood.md`.

## Стек

- **Kotlin** (`kotlin-jvm` 2.1.0, `jvmToolchain(21)`), сборка — **Gradle**
  (`./gradlew`, не Maven/kotlinc).
- **Без веб-фреймворка** — HTTP-сервер на голом `com.sun.net.httpserver.HttpServer`
  (JDK, без Ktor/Spring). Роутинг вручную через `HttpHandler`/проверку пути.
- **SQLite** (`org.xerial:sqlite-jdbc`) как хранилище индекса — таблица `chunks`
  (embedding как BLOB float32) + параллельная FTS5-таблица для BM25.
- **`org.json`** — весь JSON (запросы к Ollama/облачным LLM, `/chat` API), без
  kotlinx.serialization/Gson.
- **PDFBox/POI** — парсинг `.pdf`/`.docx`/`.doc` при индексации (`!index-doc`).
- **LLM-провайдеры**: локальная Ollama (чат-ответы юзеру, дешёвый эмбеддинг) +
  облачный мультипровайдерный `client.LlmClient` (диагностика watchdog'а, где
  нужна модель посильнее слабого VPS).
- **Деплой**: systemd на Linux VPS (`captainblood.service` + отдельный таймер
  `captainblood-watchdog.timer`), не screen/docker.

## Архитектура

```
captainblood.Main
  ├── --server (systemd) ИЛИ интерактивное меню [1]/[2]/[3]
  ├── [1] runServerMode() → PrivateLlmServer (HTTP: /chat, /health, GET / — HTML-морда)
  │         → RAG: DocumentIndex.searchHybrid() (BM25+косинус) + keyword-gate
  │           поверх тематических файлов → OllamaClient (генерация ответа)
  └── [2] runClientMode() → REPL с `!command`-диспетчером:
            !sources / !index-doc / !index-telegram / !sync-db / !reembed-all /
            !stability / !ratelimit / !maxcontext → agent.indexing.* + scp на VPS

captainblood.watchdog.WatchdogMain (отдельный процесс, запуск — systemd timer)
  → checkHealth() → если сбой: collectDiagnostics() (uptime/systemctl/ss)
    → облачная LLM ставит диагноз + выбирает ОДНО значение RecoveryAction
    → RecoveryAction.command() (код, не LLM, знает shell-команду)
    → применяет (если не --dry-run и не сработал circuit breaker) → перепроверяет /health
```

Два процесса не связаны в рантайме — только общим `/health` и общим VPS.
`captainblood` живёт под `Restart=always` (чинит ребут/падение процесса),
`watchdog` чинит то, что голый рестарт не может (например, упавшую отдельно Ollama).

## Структура папок

```
src/main/kotlin/
  captainblood/            — сам сервис: Main.kt (HTTP + REPL), OllamaClient.kt
                             (локальная генерация + tool-calling), AdminTools.kt,
                             IndexingTools.kt (обёртки над agent.indexing для REPL-команд)
  captainblood/watchdog/   — независимый процесс: WatchdogMain.kt (точка входа),
                             RecoveryAction.kt (enum допустимых действий),
                             WatchdogState.kt (circuit breaker), VpsDiagnostics.kt
  agent/indexing/          — RAG-инфраструктура: DocumentIndex (SQLite+FTS5),
                             DocumentChunker, DocumentReader (pdf/docx/txt/md),
                             EmbeddingClient (Ollama embeddings), TelegramChatReader
  agent/mcp/               — McpTool (только типы данных для tool_use)
  client/                  — LlmClient (мультипровайдерный облачный HTTP-клиент)
  core/                    — Config/ConfigKeys/InputUtils/OutputWidth (общая инфраструктура)
deploy/                    — systemd unit-файлы (.service/.timer) + logrotate
docs/                      — деплой-документация (VPS_Deploy_CaptainBlood.md)
corpus/                    — сырые файлы для индексации (перед !index-doc)
```

**Пакет = функциональная область**, не технический слой (не `controllers`/
`services`/`models`) — `captainblood.watchdog` содержит и логику, и модели
данных, и точку входа watchdog'а, потому что это одна связная подсистема.

## Naming conventions

- Файлы и классы — `PascalCase`, файл называется по главному классу/точке
  входа (`WatchdogMain.kt` → `fun main()`, `RecoveryAction.kt` → `enum class RecoveryAction`).
- Функции и переменные — `camelCase`.
- Константы верхнего уровня — `private const val SCREAMING_SNAKE_CASE`,
  с префиксом контекста, если константа привязана к конкретной подсистеме
  (`VPS_MAX_MESSAGE_CHARS`, `VPS_SAFETY_KEYWORDS`, `DEFAULT_WATCHDOG_MODEL`).
- Видимость — явная и по умолчанию узкая: `private` для всего, что не нужно
  за пределами файла, `internal` для доступного внутри модуля (`AdminTools.kt`),
  public — только когда реально вызывается из другого пакета.
- Булевы функции-предикаты — префикс `is*` (`isSafetyRelatedQuestion`,
  `isBookRelatedQuestion`, `isFictionSource`).
- Data-классы для любого структурированного результата, не `Pair`/`Triple`
  длиннее одного использования (`VpsGenConfig`, `ChatTurn`, `Decision`,
  `LocalGenResult`, `OllamaChatResult`).
- **KDoc на русском языке — обязателен для каждого `class`/`fun` уровня
  файла (не приватных однострочных хелперов)**: однострочное описание →
  пустая строка → `@param` для каждого нетривиального параметра → `@return`,
  если возвращается значимый результат. Это уже сквозная конвенция во всём
  коде проекта (`RecoveryAction.kt`, `ConfigKeys.kt`, `PrivateLlmServer`) —
  не только объясняет ЧТО, но часто и ПОЧЕМУ (см. пример 1 ниже).

## Паттерны проекта

1. **LLM выбирает из enum, код держит команду** — `RecoveryAction.kt`. Любая
   точка, где LLM влияет на реальное действие (shell-команда, SQL, файловая
   операция), должна идти через похожий allowlist, не через текст, который
   LLM формулирует сама.
2. **Keyword-gate поверх embedding-retrieval для критичных/проблемных тем** —
   `Main.kt` (`VPS_SAFETY_KEYWORDS`, `VPS_RIGGING_KEYWORDS`, `VPS_SHARK_SOURCES`
   и т.д.). Если retrieval регулярно промахивается на конкретную тему (проверено
   живыми тестами, не гипотетически) — детерминированный keyword-gate поверх
   скора embedding-модели, а не попытка подкрутить общий топ-K.
3. **REPL с `!command`-префиксом** — `Main.kt: runClientMode()`, диспетчеризация
   через `when { input.lowercase() == "!sources" -> ...; input.lowercase().startsWith("!index-doc ") -> ... }`.
   Новая команда добавляется веткой в этот `when`, плюс строка в `vpsPrintCommands()`.
4. **Централизованный `ConfigKeys`** — все имена ключей `config.properties` в
   одном `object` с комментарием назначения рядом с константой, не разбросаны
   по местам использования.
5. **Параметризованные SQL-запросы** — `DocumentIndex.kt`, `db.prepareStatement("... WHERE chunk_id = ?")`.
   `createStatement()` — только для фиксированных запросов без пользовательских
   данных (`SELECT COUNT(*) FROM chunks`).
6. **EOF ломает цикл, не продолжает его** — `if (input == null) break` во всех
   REPL (`runClientMode`, `main`) — не `continue`: закрытый stdin иначе даёт
   busy-loop, съедающий CPU.
7. **Best-effort побочный эффект — с комментарием, почему пустой catch ок** —
   `WatchdogMain.kt: logLine()`, `catch (_: Exception) { /* логирование в файл
   best-effort - отсутствие прав на запись не должно ронять watchdog */ }`.

## Хороший код — примеры

**1. Enum с безопасным выбором действия** (`src/main/kotlin/captainblood/watchdog/RecoveryAction.kt:11-40`)
```kotlin
enum class RecoveryAction {
    RESTART_OLLAMA,
    RESTART_CAPTAINBLOOD_SERVICE,
    RESTART_BOTH,
    MANUAL_REQUIRED;

    fun command(): String? = when (this) {
        RESTART_OLLAMA -> "systemctl restart ollama"
        RESTART_CAPTAINBLOOD_SERVICE -> "systemctl restart captainblood"
        RESTART_BOTH -> "systemctl restart ollama && systemctl restart captainblood"
        MANUAL_REQUIRED -> null
    }

    companion object {
        fun fromLlmAnswer(raw: String?): RecoveryAction =
            entries.find { it.name == raw?.trim() } ?: MANUAL_REQUIRED
    }
}
```
Почему хорошо: LLM не может вызвать произвольную команду — только выбрать
значение enum, и даже опечатка/мусор в ответе LLM безопасно откатывается на
`MANUAL_REQUIRED`, а не падает и не выполняет что попало.

**2. Параметризованный SQL** (`src/main/kotlin/agent/indexing/DocumentIndex.kt:85-89`)
```kotlin
db.prepareStatement("DELETE FROM chunks_fts WHERE chunk_id = ?").use {
    it.setString(1, chunkId)
    it.executeUpdate()
}
```
Почему хорошо: `?`-плейсхолдер, не конкатенация строки — даже если `chunkId`
однажды будет приходить из внешнего источника, инъекция невозможна.

**3. Keyword-gate вместо доверия голому скору** (`src/main/kotlin/captainblood/Main.kt:316-326`)
```kotlin
private val VPS_SAFETY_KEYWORDS = listOf(
    "тонет", "тонущ", "утонул", "утопа", "спасти", "спасени",
    "медуз", "судорог", "шторм", "молни", "ожог", "перегрев",
    "течени", "нырять", "нырк", "тонуть"
)

private fun isSafetyRelatedQuestion(question: String): Boolean {
    val lower = question.lowercase()
    return VPS_SAFETY_KEYWORDS.any { lower.contains(it) }
}
```
Почему хорошо: найдено вживую, что embedding-скор для правильного чанка про
безопасность может проиграть случайному нерелевантному чанку на доли процента —
для тем, где цена ошибки высока (безопасность), решение не оставлено на волю
скора модели.

**4. REPL-диспетчер по префиксу команды** (`src/main/kotlin/captainblood/Main.kt:1567-1592`)
```kotlin
when {
    input.lowercase() == "!sources" -> {
        val index = DocumentIndex(VPS_DB_PATH)
        try { optPrintSources(index) } finally { index.close() }
    }
    input.lowercase().startsWith("!index-doc ") -> {
        val path = input.substring("!index-doc ".length).trim()
        runLocalIndexAndSync(config) { index, embClient -> indexDocument(path, index, embClient) }
    }
    else -> sendSingleChat(baseUrl, login, password, input)
}
```
Почему хорошо: явный, легко расширяемый список веток; ресурсы (`DocumentIndex`)
закрываются через `try/finally`/`.use`, а не оставляются на GC.

**5. KDoc объясняет ПОЧЕМУ, не только ЧТО** (`src/main/kotlin/captainblood/watchdog/RecoveryAction.kt:29-37`)
```kotlin
/**
 * Разбирает ответ LLM в одно из значений enum по точному совпадению имени.
 *
 * @param raw сырое значение поля `action` из JSON-ответа LLM
 * @return найденное значение enum, либо [MANUAL_REQUIRED] при любом несовпадении
 *         (опечатка, посторонний текст, неизвестное значение) — безопасный дефолт
 *         вместо попытки угадать намерение модели
 */
```
Почему хорошо: комментарий фиксирует ИНВАРИАНТ (небезопасный ввод → безопасный
дефолт), а не пересказывает сигнатуру функции.

## Антипаттерны — запрещено

1. **LLM не пишет shell-команду/SQL-запрос текстом, который потом
   исполняется как есть.** Только выбор ИЗ заранее написанного и
   провалидированного человеком allowlist (см. `RecoveryAction`). Формулировка
   вида "модель вернула команду, мы её executeShell(...)" — недопустима.
2. **Конкатенация пользовательских/динамических данных в SQL-строку.**
   Только `prepareStatement` с `?`. `"SELECT * FROM chunks WHERE source = '$source'"` —
   запрещено, даже если source сейчас всегда из доверенного источника.
3. **`continue` вместо `break` на EOF (`null`) от `readlnWithFlush()`/`readlnOrNull()`
   в REPL-цикле.** Закрытый stdin даёt бесконечный busy-loop, жрущий CPU —
   реальный найденный баг паттерна, исправленный явно.
4. **Пустой `catch` без комментария, почему это осознанно.** Любой проглоченный
   `Exception` обязан объяснить рядом, почему сбой здесь не должен ронять
   вызывающий код (см. `logLine` в `WatchdogMain.kt` — best-effort-логирование).
   Просто `catch (e: Exception) {}` без пояснения — не проходит ревью.
5. **Реальные секреты в файле, который может попасть в git.** `config.properties`
   с боевыми значениями — только локально, гитигнорится; в репозитории —
   только `config.properties.example` с плейсхолдерами вида `<openrouter_api_key>`.
6. **`pdfbox-app` (fat jar).** Конфликтует с POI по версии `commons-io` —
   использовать только раздельные артефакты (`pdfbox`, `fontbox`, отдельно
   `commons-logging`), см. зависимости в `build.gradle.kts`.
7. **Доверять только скору embedding-модели там, где цена ошибки высока**
   (безопасность, конкретные факты/атрибуция) — без явного детерминированного
   keyword-gate поверх, если retrieval уже подводил на этой теме живьём.

## Чек-лист: новый HTTP-эндпоинт

*(v2 — добавлено 2026-07-20 по итогам тестовой генерации `GET /stats`: агент
принял разумные, но НИГДЕ не проговорённые в правилах решения — это и есть
пробел, который правила должны закрывать явно.)*

При добавлении любого нового публичного HTTP-эндпоинта в `PrivateLlmServer`
явно ответить на три вопроса — и зафиксировать ответ в KDoc метода-хендлера:

1. **Нужна ли авторизация?** По умолчанию — да, через `sharedAuthenticator()`
   (как `/` и `/chat`). Без авторизации — только если эндпоинт отдаёт
   исключительно диагностическую информацию без содержимого чата (как
   `/health`) — и это обоснование обязано быть написано в KDoc, а не
   подразумеваться "по аналогии".
2. **Нужен ли `RateLimiter`?** По умолчанию — да, если эндпоинт делает
   нетривиальную работу (запрос к БД, вызов LLM). Лёгкие проверочные
   эндпоинты (health/stats) можно пропустить, но опять же — с явным
   обоснованием в KDoc, а не молчаливым пропуском.
3. **Обновлён ли `README.md`?** Раздел «Состав»/список эндпоинтов в README
   должен перечислять любой новый публичный путь наравне с `/chat`/`/health` —
   README не должен расходиться с реальным HTTP-интерфейсом сервиса.

## Шаблон типичного файла

```kotlin
package captainblood.<подпакет, если нужен>

import agent.indexing.DocumentIndex   // сначала свои модули проекта...
import client.LlmClient
import core.ConfigKeys
import org.json.JSONObject            // ...затем внешние библиотеки
import java.io.File                   // ...затем stdlib/JDK

/** Константа с очевидным назначением — без комментария, если имя говорит само за себя. */
private const val DEFAULT_TIMEOUT_MS = 5_000

/**
 * Константа, где важно ПОЧЕМУ именно такое значение — комментарий обязателен.
 */
private const val VPS_SOMETHING_LIMIT = 25

/**
 * Что делает класс/функция — одно предложение.
 *
 * Дальше — контекст/причина решения, если она не очевидна из кода (не всегда
 * нужно, но если решение неочевидное — не экономить на объяснении).
 *
 * @param someParam что это и почему такое значение по умолчанию (если есть)
 * @return что возвращается и какие крайние случаи (null/пусто/дефолт) означают
 */
fun doSomething(someParam: String): String? {
    TODO()
}

/** Приватный однострочный хелпер — KDoc можно короче, но не пропускать совсем. */
private fun helper(x: Int): Boolean = x > 0
```

## Как работать над этим проектом с Claude Code

- **Глобальный конфиг** (`~/.claude/CLAUDE.md` + `~/.claude/rules/*.md`) —
  действует автоматически в любой сессии на этой машине: KDoc на русском,
  CRLF для новых файлов, подтверждение перед каждым `git commit`,
  `~/.claude/rules/SETUP_ENVIRONMENT.md` (машинное окружение — Ollama/Gradle/
  ast-index) и `~/.claude/rules/ast-index.md` (поиск по коду).
- **Локальный конфиг** — этот файл. Специфичные для CaptainBlood правила
  (стек/архитектура/паттерны/антипаттерны выше) имеют приоритет над общими
  рассуждениями "как обычно пишут на Kotlin", если противоречат.
- **Субагенты** — по глобальному правилу использовать `cavecrew-investigator`
  (найти определения/использования), `cavecrew-builder` (правки в 1-2 файлах),
  `cavecrew-reviewer` (ревью изменений) для экономии токенов вместо ручного
  `Explore`/чтения файлов целиком.
- **Скиллы** — `ast-index` пока НЕ инициализирован в этом репозитории (нет
  `.claude/`) — при первой работе с кодом выполнить `ast-index rebuild`
  (Gradle-проект, автоопределение стека должно сработать, в отличие от
  `aiexperiment` на чистом kotlinc). После `git pull`/добавления файлов —
  `ast-index update`.
