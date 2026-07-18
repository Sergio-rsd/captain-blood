package captainblood

import agent.indexing.DocumentIndex
import agent.indexing.EmbedTask
import agent.indexing.EmbeddingClient
import com.sun.net.httpserver.BasicAuthenticator
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import core.ConfigKeys
import core.loadConfigOrNullWithMessage
import core.printlnColumn
import core.readlnWithFlush
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// =====================================================================
// Константы (Week 6 День 30 — приватный LLM-сервис на VPS)
// =====================================================================

/**
 * Отдельный корпус Дня 30 (не тот же, что Week6Day2-4/Дни 27-29) — эрудит-бот
 * "старый морской волк" про море/океан/пиратов для внука друзей, копируется на VPS
 * как есть, без переиндексации там.
 */
private const val VPS_DB_PATH = "adventures_sea.db"

/**
 * Модель ответа сервиса — после апгрейда VPS (2 vCPU / 8GB RAM, 2026-07-13) переключено
 * с `3b` на `7b`: живой A/B на VPS (2 запроса, простой + составной вопрос) показал рост
 * времени ответа в ~2.8-3× (12с→34с, 18с→56с — CPU как было единственным узким местом, так
 * и осталось, объём вычислений на токен у 7b просто больше), но `7b` живьём исправляет
 * документированный баг Дня 30: `3b` на составном вопросе про несколько акул перепутала
 * факты между сущностями (спокойную характеристику акулы-няньки скопировала на белую акулу),
 * `7b` развела три сущности верно. Решение пользователя — качество важнее скорости, раз
 * задержка и так привычна.
 */
private const val VPS_MODEL = "qwen2.5:7b-instruct"

/** Единственный адрес сервиса — другого пока нет, поэтому не спрашиваем в клиент-режиме. */
private const val VPS_CLIENT_BASE_URL = "http://31.97.125.243:8080"

/**
 * Модель LLM-судьи шума при `!index-telegram` — используется ТОЛЬКО локально (индексация
 * идёт на машине разработчика, не на слабом VPS), поэтому можно взять более сильную
 * 7b-модель, доступную на этой машине (Дни 26-29), а не ограничиваться [VPS_MODEL].
 */
private const val VPS_LOCAL_INDEX_JUDGE_MODEL = "qwen2.5:7b-instruct"

/** Порог LLM-судьи шума при индексации (0-10) — тот же дефолт, что и в Дне 29. */
private const val VPS_NOISE_THRESHOLD = 4

private const val VPS_TEMPERATURE = 0.2
private const val VPS_NUM_CTX = 4096
private const val VPS_NUM_PREDICT = 512
private const val VPS_TOP_K = 3
private const val VPS_MIN_SCORE = 0.3f

/**
 * Число чанков, которые берутся из приоритизированного файла в keyword-gate ветках
 * ([isSafetyRelatedQuestion]/[isRiggingRelatedQuestion]/[isSharkRelatedQuestion]/
 * [isWeaponsRelatedQuestion]/[isLifeRelatedQuestion]) — больше [VPS_TOP_K], потому что даже
 * внутри уже найденного нужного файла самый релевантный чанк не всегда попадает в топ-3 по
 * скору embedding-модели (найдено на живом тесте: "как одевались пираты?" не подтягивал
 * абзац про клёш/пояс, теряя его среди соседних чанков того же файла).
 */
private const val VPS_TOPIC_TOP_K = 5

/**
 * Размер «сырого» пула перед фильтрацией книжных чанков — больше [VPS_TOP_K], чтобы после
 * исключения книги (см. [answerWithRag]) всё равно оставалось достаточно фактов на выбор.
 */
private const val VPS_SEARCH_POOL = 25

/**
 * Размер пула для гибридного (BM25 + косинус, см. [DocumentIndex.searchHybrid]) поиска в
 * keyword-gate ветках — эмулирует прежний "полный скан" (было `topK = Int.MAX_VALUE` на
 * чистом косинусе), но searchHybrid требует конечных topK/poolSize с обеих сторон слияния.
 * Больше размера всего корпуса (~2900 чанков на 2026-07-13) — то есть фактически полный пул.
 */
private const val VPS_FULL_POOL_SIZE = 3000

/**
 * Максимальная длина входящего сообщения в символах — это и есть проверяемое заданием
 * ограничение «max context»: запрос отбрасывается ДО эмбеддинга/поиска/генерации, чтобы
 * не тратить ресурсы слабого VPS (1 vCPU) на заведомо неприемлемый запрос.
 */
private const val VPS_MAX_MESSAGE_CHARS = 1000

/**
 * Скользящее окно истории чата — сколько последних пар "вопрос-ответ" передавать модели.
 * Больше — лучше держит контекст диалога, но и больше нагрузка на слабый 1 vCPU VPS на
 * КАЖДЫЙ запрос; 6 — середина обсуждённого диапазона 5-8.
 */
private const val VPS_HISTORY_MAX_PAIRS = 6

/**
 * Метка в имени источника, по которой файл художественной книги про капитана Блада
 * сознательно исключается из основного retrieval-пула [answerWithRag] (кроме
 * [bookExcerptHint]), чтобы книга не "вылезала" дословно и не путалась с фактическим
 * корпусом. Все 4 части серии уже названы по этому шаблону (`odisseia-kapitana-blada.txt`,
 * `udaci-kapitana-blada.txt` и т.д.).
 *
 * По умолчанию НОВЫЙ файл корпуса — любого расширения (.txt/.md/.pdf) — считается фактом и
 * участвует в обычном RAG без единой правки кода. Правки требует только добавление ЕЩЁ ОДНОЙ
 * художественной книги: либо назвать её файлы по тому же шаблону, либо расширить проверку
 * в [isFictionSource].
 *
 * Раньше исключение определялось по расширению (`source.endsWith(".txt")`), что было
 * ошибкой: когда в корпус добавили `piratstvo-karibi-sea.txt` (реальная статья про пиратов
 * Карибского моря, включая факт про реального Генри Моргана), файл целиком выпал из RAG под
 * тем же фильтром, что и вымышленный Блад — модель отвечала про Моргана вообще без контекста
 * и путала, реальный он или нет. Расширение файла не говорит о его жанре (файл теперь
 * переименован в `content_PiratstvoKaribskogoMorya.md` и участвует в общем пуле наравне с
 * остальным корпусом).
 */
private const val VPS_FICTION_MARKER = "kapitana-blada"

/** Определяет, является ли источник частью художественной книги — см. [VPS_FICTION_MARKER]. */
private fun isFictionSource(source: String): Boolean = source.contains(VPS_FICTION_MARKER)

private const val VPS_PORT = 8080
private const val VPS_SERVER_THREADS = 4
private const val VPS_RATE_LIMIT_MAX = 5
private const val VPS_RATE_LIMIT_WINDOW_MS = 30_000L
private const val VPS_STABILITY_DEFAULT_N = 5

/**
 * Персона "старый морской волк" — эрудит-бот про море/океан для внука друзей (12 лет).
 * Короче, чем `CMP_ANSWER_SYSTEM`/`OPT_ANSWER_SYSTEM` Дней 28-29: 1.5b/3b хуже держат длинные
 * многосоставные инструкции, чем 7b. Книга ("Одиссея капитана Блада") НЕ индексируется в
 * контекст (см. [VPS_FICTION_MARKER] в [answerWithRag]) — тизер про сюжет строится на общих
 * знаниях модели о книге, а не на retrieved-тексте, поэтому книга не "вылезает" дословно.
 */
private val VPS_ANSWER_SYSTEM_BASE = """
Ты — старый морской волк, повидавший океаны, рассказываешь мальчику 12 лет, который
сейчас отдыхает у моря. Ты знаешь толк не только в морских обитателях, но и в
кораблях, пиратах и жизни на море в XVII веке.

Правила:
- Отвечай простыми словами, живо и с азартом старого морехода, но без выдумок.
- Опирайся на факты из приведённого контекста, если он отвечает на вопрос; если в
  контексте ответа нет, но ты уверен в факте из общих знаний — отвечай сам.
- Если не уверен в факте или цифре — честно скажи "точно не знаю", не выдумывай.
- Никогда не копируй текст дословно — всегда переформулируй своими словами.
- Изредка вплетай байки про пиратов, парусники, Карибы и медицину эпохи паруса.
- Никогда не затрагивай пугающие, жестокие или недетские темы — если вопрос об этом,
  мягко переведи разговор на что-то дружелюбное про море.
- Отвечай кратко: 2-4 предложения, на русском языке.
- Не упоминай, что ты используешь контекст, инструкции или базу данных — просто
  отвечай по существу.
""".trimIndent()

/**
 * Правило про тизер книги — добавляется к системному промпту ТОЛЬКО когда вопрос сам
 * содержит ключевые слова про книгу (см. [VPS_BOOK_KEYWORDS]/[isBookRelatedQuestion]).
 * Причина: на живом тесте модель (3b) не смогла надёжно выполнять условие "делай X ТОЛЬКО
 * если Y" внутри одного промпта — тизер "читай в книге!" прилипал даже к вопросам про
 * капитана Кука и к "как тебя зовут?". Детерминированный gate в коде, а не в тексте
 * инструкции, устраняет это полностью — модель просто не видит правило, если вопрос не
 * про книгу.
 */
private val VPS_BOOK_TEASER_RULE = """
Мальчик читает серию книг Рафаэля Сабатини про капитана Питера Блада (в неё входит
несколько книг — например, "Одиссея капитана Блада" и другие про его приключения),
и вопрос сейчас именно про эту серию. Важный факт: Питер Блад — ПОЛНОСТЬЮ ВЫМЫШЛЕННЫЙ
персонаж художественных книг, он НИКОГДА реально не существовал и у него нет
настоящей могилы — если спросят, существовал ли он на самом деле, честно скажи, что
это выдуманный герой книг, а не реальный человек.
Эти базовые факты сюжета — canon, называй их уверенно, не отрицай и не путай: по
профессии Питер Блад — врач; его несправедливо обвинили в мятеже и продали в рабство
на остров Барбадос; позже он бежал из рабства, стал капитаном и капером (не простым
пиратом — действовал по каперскому патенту) в Карибском море, но остался благородным
и справедливым человеком; его базой (пиратским оплотом, где он останавливался между
плаваниями) был остров Тортуга — НЕ Куба и не какой-то другой остров.
Сначала дай короткий содержательный ответ по существу вопроса (1-2 предложения) — не
отвечай ТОЛЬКО тизером без содержания. ОБЯЗАТЕЛЬНО назови в этом ответе автора (Рафаэль
Сабатини) и название хотя бы первой книги серии — "Одиссея капитана Блада" — это точно
известные, не спойлерные факты, их можно и нужно называть прямо, не стесняясь. За
пределами уже перечисленных здесь фактов (сюжет, автор, название первой книги) не
придумывай новые детали (названия ОСТАЛЬНЫХ книг серии, имена кораблей, второстепенных
персонажей, номера глав, развязку истории — ты можешь их не помнить верно или это
испортит интригу). Затем заверши фразой "а что будет дальше — читай в книге о
приключениях капитана Блада!"
""".trimIndent()

private val VPS_BOOK_KEYWORDS = listOf("блад", "сабатини", "одиссе", "хроник", "удач")

/**
 * Определяет, упоминает ли вопрос (или недавняя история от лица "юнги") книгу/персонажа —
 * включает [VPS_BOOK_TEASER_RULE] в промпт (и, начиная с 2026-07-13, подстановку автора/
 * названия — см. [answerWithRag]). Проверяет ПОСЛЕДНИЙ вопрос юнги в истории тоже: иначе
 * короткое уточнение вроде "а кто написал?" сразу после вопроса про Блада не попадёт под
 * тизер.
 *
 * Найдено на живом тесте 2026-07-13 (запись видео): без ограничения `matchesOtherTopic`
 * ниже книга "прилипала" вообще ко ВСЕМ следующим вопросам до конца разговора, даже про
 * фордевинд и спасение утопающего — стоило хоть раз спросить про Блада. Раз текущий вопрос
 * сам явно попадает в другую тему (такелаж/акулы/оружие/быт/безопасность/история/
 * знакомство) — это точно смена темы, а не уточнение про книгу, и история игнорируется.
 */
private fun isBookRelatedQuestion(question: String, history: List<ChatTurn>): Boolean {
    val lower = question.lowercase()
    if (VPS_BOOK_KEYWORDS.any { lower.contains(it) }) return true

    val matchesOtherTopic = isSafetyRelatedQuestion(question) || isRiggingRelatedQuestion(question) ||
        isSharkRelatedQuestion(question) || isWeaponsRelatedQuestion(question) ||
        isLifeRelatedQuestion(question) || isHistoryRelatedQuestion(question) || isIdentityQuestion(question)
    if (matchesOtherTopic) return false

    val lastUserText = history.lastOrNull { it.role == "user" }?.text ?: return false
    return VPS_BOOK_KEYWORDS.any { lastUserText.lowercase().contains(it) }
}

/**
 * Доля чанков от начала каждой книги (по номеру `chunk_N` в поле `section` чанка для
 * fixed-стратегии — чанки идут строго по порядку текста), которую разрешено отдавать
 * в [bookExcerptHint]. Остаток (кульминация/развязка) отсекается — анти-спойлер.
 */
private const val VPS_BOOK_HINT_MAX_FRACTION = 0.7

/**
 * Максимум отрывков из книги за один вызов [bookExcerptHint]. Перепробовано: (а) по одному
 * на книгу серии ([distinctBy] по source) — отрывки из разных, не связанных друг с другом
 * сцен модель (3b) сшивала в один противоречивый рассказ; (б) топ-2 по скору без distinctBy
 * — те же 2 отдельных отрывка ВСЁ РАВНО иногда из разных сцен, и модель путает, кто на кого
 * напал (реплика второстепенного персонажа "меня атаковал пират Блад" превращалась в "Блад
 * напал на Блада"). Остановились на 1 — модель заметно надёжнее не путает субъект
 * повествования внутри ОДНОЙ связной сцены, чем пытаясь свести две разные.
 */
private const val VPS_BOOK_HINT_MAX_EXCERPTS = 1

/**
 * Достаёт несколько коротких отрывков из настоящего текста книги про капитана Блада —
 * подмешивается в промпт как более весомая фактическая опора (см. [VPS_BOOK_TEASER_RULE]).
 * Расширено с одного предложения до нескольких отрывков по прямому запросу пользователя
 * ("книгу закопали слишком глубоке, непонятно, к чему призыв читать") — книга по-прежнему
 * не в основном пуле [answerWithRag] (не хотим дословных простыней текста), но голых
 * anchor-фактов и одного предложения оказалось мало, чтобы модель (3b) не путала детали,
 * которых нет в anchor-списке (см. пример с "базой на Кубе" вместо Тортуги).
 *
 * Анти-спойлер: чанки из последней трети каждой из 4 книг серии (см.
 * [VPS_BOOK_HINT_MAX_FRACTION]) отбрасываются — там обычно кульминация и развязка, а цель
 * подсказки — заинтересовать, а не пересказать весь сюжет.
 *
 * @param index          открытый индекс (переиспользуется, не закрывается здесь)
 * @param queryEmbedding эмбеддинг вопроса
 * @return до [VPS_BOOK_HINT_MAX_EXCERPTS] отрывков (разделены переводом строки) или null
 */
private fun bookExcerptHint(index: DocumentIndex, queryEmbedding: FloatArray): String? {
    val candidates = index.search(queryEmbedding, topK = 20)
        .filter { isFictionSource(it.chunk.source) && it.score >= VPS_MIN_SCORE && it.chunk.strategy == "fixed" }
    if (candidates.isEmpty()) return null

    val totalBySource = candidates.map { it.chunk.source }.distinct()
        .associateWith { index.countBySource(it, "fixed") }

    val excerpts = candidates
        .filter { hit ->
            val n = hit.chunk.section.removePrefix("chunk_").toIntOrNull() ?: return@filter true
            val total = totalBySource[hit.chunk.source] ?: return@filter true
            total == 0 || n < total * VPS_BOOK_HINT_MAX_FRACTION
        }
        // НЕ distinctBy source: разнообразие по всем 4 книгам сразу даёт отрывки из
        // не связанных друг с другом сцен — модель (3b) сшивала их в один противоречивый
        // рассказ (см. комментарий у VPS_BOOK_HINT_MAX_EXCERPTS). Пусть топ-N по скору
        // естественно кластеруются вокруг одной сцены/книги, к которой ближе вопрос.
        .take(VPS_BOOK_HINT_MAX_EXCERPTS)
        .mapNotNull { hit ->
            hit.chunk.text
                .split(Regex("(?<=[.!?])\\s+"))
                .filter { it.isNotBlank() }
                .take(5)
                .joinToString(" ")
                .trim()
                .take(500)
                .takeIf { it.isNotBlank() }
        }

    return excerpts.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/**
 * Файл с правилами безопасности на воде — единственный из корпуса, где важна не просто
 * точность, а гарантированная выдача правильного совета (см. [isSafetyRelatedQuestion]).
 */
private const val VPS_SAFETY_SOURCE = "content_BezopasnostNaVode.md"

/**
 * Ключевые слова, по которым вопрос считается про безопасность на воде — при совпадении
 * [answerWithRag] принудительно приоритизирует чанки из [VPS_SAFETY_SOURCE] в контексте,
 * а не полагается на голый скор embedding-модели.
 *
 * Причина (найдено на живом тесте 2026-07-11): для вопроса "как спасти тонущего в море?"
 * embedding-модель (`nomic-embed-text`) дала правильному чанку про безопасность (0.7439)
 * оценку НИЖЕ, чем случайно попавшему не по теме чанку про такелаж (0.7654) — все кандидаты
 * сидели в узком диапазоне 0.74-0.77, и правильный чанк проиграл на долю процента. Для
 * безопасности это неприемлемо: детерминированный keyword-gate надёжнее общего скора.
 */
private val VPS_SAFETY_KEYWORDS = listOf(
    "тонет", "тонущ", "утонул", "утопа", "спасти", "спасени",
    "медуз", "судорог", "шторм", "молни", "ожог", "перегрев",
    "течени", "нырять", "нырк", "тонуть"
)

/** Определяет, касается ли вопрос безопасности на воде — см. [VPS_SAFETY_KEYWORDS]. */
private fun isSafetyRelatedQuestion(question: String): Boolean {
    val lower = question.lowercase()
    return VPS_SAFETY_KEYWORDS.any { lower.contains(it) }
}

/**
 * Файл про такелаж, ветер и курсы парусника — та же природа проблемы, что и у
 * [VPS_SAFETY_SOURCE] (см. [VPS_SAFETY_KEYWORDS]): найдено на живом тесте 2026-07-12
 * (готовясь к сдаче), что "как называется ветер в корму?" и "а корма у корабля где?"
 * не подтягивали этот файл вообще (в топ-3 попадали случайные чанки из совсем других
 * файлов про кораблекрушения и байки со скором 0.77-0.81) — модель на пустом контексте
 * уверенно выдумывала и даже путала фордевинд (благоприятный курс) с опасным поворотом
 * через фордевинд.
 */
private const val VPS_RIGGING_SOURCE = "content_TakelazhIVeter.md"

private val VPS_RIGGING_KEYWORDS = listOf(
    "ветер", "корм", "нос корабл", "борт", "парус", "курс корабл", "галс",
    "фордевинд", "бейдевинд", "галфвинд", "бакштаг", "мачт", "такелаж",
    "штурвал", "руль", "киль", "рей", "гик", "гафель", "кливер", "стаксель",
    "бушприт", "вант", "штаг", "румб", "оверштаг", "шпангоут"
)

/**
 * Явный анкер-факт про фордевинд — найдено на живом тесте 2026-07-13 (запись видео):
 * несмотря на корректный retrieved-контекст (в файле чётко написано "курс точно ПО
 * ветру"), модель (3b) при переформулировке сама себе противоречила в одном ответе —
 * "идёт точно ПРОТИВ ветра... это благоприятный, попутный ветер". Скорее всего модель
 * зацепилась за фразу "точно против ветра", которая в том же файле встречается рядом
 * в двух ДРУГИХ контекстах ("мёртвая зона", лавировка) — и перепутала её местами при
 * пересказе своими словами. Тот же класс проблемы, что и anchor-факты про Тортугу/
 * книгу: детерминированное правило в промпте надёжнее, чем полагаться на то, что 3b
 * не переврёт формулировку при парафразе.
 */
private val VPS_RIGGING_ANCHOR_RULE = """
Важный факт, не путай: фордевинд — это курс точно ПО ветру (попутный, благоприятный,
самый быстрый и лёгкий курс), а НЕ против ветра. Против ветра парусник вообще плыть
не может ("мёртвая зона"). Не путай сам курс фордевинд (безопасный) с манёвром
"поворот через фордевинд" (это другое понятие, он резкий и рискованный для команды).
""".trimIndent()

/** Определяет, касается ли вопрос такелажа/ветра/курсов — см. [VPS_RIGGING_KEYWORDS]. */
private fun isRiggingRelatedQuestion(question: String): Boolean {
    val lower = question.lowercase()
    return VPS_RIGGING_KEYWORDS.any { lower.contains(it) }
}

/**
 * Файлы про виды акул — та же природа проблемы, что и у такелажа/безопасности (см.
 * [VPS_RIGGING_KEYWORDS]): найдено на живом тесте 2026-07-12, что уточняющий вопрос
 * "белая акула спокойная, если её не трогать?" при VPS_TOP_K=3 не дотягивал до нужного
 * уточняющего чанка про разницу белой акулы и акулы-няньки — модель смешивала факты
 * двух разных акул в одном ответе.
 *
 * МНОЖЕСТВО, а не одна строка — исправлено 2026-07-13 по тому же паттерну, что и
 * VPS_WEAPONS_SOURCES: добавлен второй файл (`shark-karibi.txt`), и жёсткий буст ТОЛЬКО под
 * content_VidyAkul.md вытеснял второй файл из TOPIC_TOP_K слотов. Живой баг: "расскажи про
 * бычью акулу" (вид есть только в shark-karibi.txt) — модель насочиняла (Африка/Австралия/
 * Южная Америка, 3-4 метра) на контексте из content_VidyAkul.md, где бычьей акулы нет вообще.
 */
private val VPS_SHARK_SOURCES = setOf("content_VidyAkul.md", "shark-karibi.txt")

private val VPS_SHARK_KEYWORDS = listOf(
    "акул", "мегалодон"
)

/** Определяет, касается ли вопрос акул — см. [VPS_SHARK_KEYWORDS]. */
private fun isSharkRelatedQuestion(question: String): Boolean {
    val lower = question.lowercase()
    return VPS_SHARK_KEYWORDS.any { lower.contains(it) }
}

/**
 * Файлы про оружие пиратов — та же природа проблемы: найдено на живом тесте 2026-07-12,
 * что "чем вооружены пираты?" вообще не подтягивал файл (retrieval нашёл байки и
 * безопасность вместо оружия), и модель на пустом контексте выдумала анахронизмы
 * (револьверы, катапульты) вместо реальных сабель, пистолетов и пушек из файла.
 *
 * МНОЖЕСТВО, а не одна строка — исправлено 2026-07-13: когда добавили второй файл на
 * ту же тему (`pirate-weapon.txt`, через `!index-doc`), приоритет доставался ТОЛЬКО
 * `content_OruzhiePiratov.md` (буст брал `VPS_TOPIC_TOP_K` чанков строго из него, если
 * вопрос совпадал с этим файлом впрямую — например, "абордажная сабля" есть в обоих
 * файлах), и второй файл проигрывал за оставшиеся 0 слотов. Живой баг: вопрос "как ещё
 * называлась абордажная сабля?" — `pirate-weapon.txt` называет термин "катлас", но
 * ответ ссылался только на `content_OruzhiePiratov.md` ("кортик, тесак") и модель
 * следующим вопросом уверенно ОТРИЦАЛА, что "катлас" вообще существующее название.
 */
private val VPS_WEAPONS_SOURCES = setOf("content_OruzhiePiratov.md", "pirate-weapon.txt", "canon-pirati-karibi.txt")

private val VPS_WEAPONS_KEYWORDS = listOf(
    "оружи", "вооруж", "сабл", "шпаг", "палаш", "пистолет", "пушк", "ядро",
    "картечь", "крюк", "абордаж", "полундра", "катлас", "топор", "дирк", "нож", "кинжал"
)

/** Определяет, касается ли вопрос оружия пиратов — см. [VPS_WEAPONS_KEYWORDS]. */
private fun isWeaponsRelatedQuestion(question: String): Boolean {
    val lower = question.lowercase()
    return VPS_WEAPONS_KEYWORDS.any { lower.contains(it) }
}

/**
 * Файл про быт пиратов (одежда, еда, ром) — тот же устоявшийся паттерн, что и у оружия/
 * акул/такелажа: новый тематический файл почти всегда теряется в общем retrieval-пуле без
 * явного keyword-gate (см. VPS_WEAPONS_KEYWORDS).
 */
private const val VPS_LIFE_SOURCE = "content_BytPiratov.md"

private val VPS_LIFE_KEYWORDS = listOf(
    "одежд", "одева", "во что был", "серьг", "повязк на глаз", "сухар",
    "солонин", "цинга", "лайм", "ром", "грог"
)

/** Определяет, касается ли вопрос быта пиратов — см. [VPS_LIFE_KEYWORDS]. */
private fun isLifeRelatedQuestion(question: String): Boolean {
    val lower = question.lowercase()
    return VPS_LIFE_KEYWORDS.any { lower.contains(it) }
}

/**
 * Файл про реальных пиратов Карибского моря (Морган, Чёрная Борода и др.) — тот же паттерн,
 * что и у оружия/акул/такелажа/быта. Найдено на живом тесте 2026-07-12: вопрос "кто такой
 * Генри Морган?" не подтягивал этот файл вообще (раньше он и не мог участвовать в retrieval —
 * был исключён вместе с художественной книгой, см. [VPS_FICTION_MARKER]), и модель без
 * контекста путала реального Моргана с вымышленным капитаном Бладом.
 */
private const val VPS_HISTORY_SOURCE = "content_PiratstvoKaribskogoMorya.md"

private val VPS_HISTORY_KEYWORDS = listOf(
    "морган", "тич", "чёрная борода", "черная борода", "робертс", "чёрный барт",
    "черный барт", "амаро парго", "флибустьер", "капер", "корсар", "береговое братство",
    "картахен", "порт-ройял", "порт ройял", "тортуг"
)

/** Определяет, касается ли вопрос реальных пиратов Карибского моря — см. [VPS_HISTORY_KEYWORDS]. */
private fun isHistoryRelatedQuestion(question: String): Boolean {
    val lower = question.lowercase()
    return VPS_HISTORY_KEYWORDS.any { lower.contains(it) }
}

/**
 * Вопросы-знакомства ("ты кто", "как тебя зовут") — тот же класс проблемы, что и с книгой/
 * безопасностью: модель на живом тесте вместо представления сразу уходила в первый попавшийся
 * найденный факт (например, про китов). Здесь решение ещё жёстче — не просто приоритизировать
 * retrieval, а вообще пропустить и retrieval, и саму генерацию: готовый ответ гарантированно
 * в характере и без риска утянуть что-то не по теме.
 */
private val VPS_IDENTITY_KEYWORDS = listOf(
    "ты кто", "кто ты", "как тебя зовут", "представ", "познаком", "здравств",
    "добрый день", "доброе утро", "добрый вечер", "привет"
)

private fun isIdentityQuestion(question: String): Boolean {
    val lower = question.lowercase()
    return VPS_IDENTITY_KEYWORDS.any { lower.contains(it) }
}

private const val VPS_IDENTITY_ANSWER =
    "Я старый морской волк — бывалый капитан, повидавший немало морей и океанов! " +
        "Спрашивай что хочешь — расскажу про рыб, корабли, пиратов, штормы и клады."

/**
 * Ответ, когда сервис уже занят чужим вопросом — см. [PrivateLlmServer.busy].
 * VPS — 1 vCPU без GPU, одновременно тянуть две генерации физически нечем: без этой
 * проверки второй вопрос молча вставал в очередь и ждал первого (иногда несколько минут),
 * что для нетерпеливого 12-летнего читателя выглядит как "зависло", а не "занято".
 */
private const val VPS_BUSY_ANSWER =
    "Погоди чуток, юнга — капитан сейчас отвечает другому! Повтори вопрос через полминутки."

/**
 * Ответы на холодный старт — модель [VpsGenConfig.model] ещё не загружена в память Ollama
 * (см. [OllamaClient.isModelLoaded]), первый вызов к ней грузит веса с диска и может занять
 * несколько минут на слабом VPS без GPU. Без этой проверки такой запрос молча висел до
 * [OllamaClient] `readTimeout` (5 минут) или до путающего [VPS_BUSY_ANSWER], если юнга не
 * дождался и переспросил, — теперь честно объясняем задержку. Несколько вариантов, чтобы
 * при паре холодных стартов подряд (обычно один — сразу после рестарта сервиса) не звучало
 * одинаково; выбирается случайно в [PrivateLlmServer.handleChat].
 */
private val VPS_WARMING_ANSWERS = listOf(
    "Погоди, юнга — капитан только продрал глаза и вылезает из гамака! Спроси ещё разок через полминутки.",
    "Ох и заспался я сегодня... Дай капитану прийти в себя, повтори вопрос чуть погодя!",
    "Штиль в голове спросонья, юнга! Обожди немного, сейчас соберусь с мыслями и отвечу.",
    "Только глаза разлепил после сна — не гони лошадей, то бишь корабль! Переспроси через полминутки."
)

/**
 * Статичная HTML-морда поверх `/chat` — судовой журнал вместо обычных чат-пузырей.
 * Отдаётся по `GET /` под тем же HTTP Basic Auth, что и `/chat` (см. [PrivateLlmServer.start]):
 * браузер один раз спрашивает логин/пароль нативным окном и дальше сам подставляет их во все
 * fetch-запросы того же источника — никаких паролей в открытом виде в самой странице.
 */
private fun vpsIndexHtml(showSources: Boolean): String {
    val showSourcesFlag = if (showSources) "true" else "false"
    return """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>Судовой журнал — Старый морской волк</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ccircle cx='50' cy='50' r='46' fill='%230d3b46'/%3E%3Ccircle cx='50' cy='50' r='40' fill='none' stroke='%23d9a63f' stroke-width='3'/%3E%3Ccircle cx='50' cy='50' r='4' fill='%23d9a63f'/%3E%3Cline x1='50' y1='12' x2='50' y2='88' stroke='%23d9a63f' stroke-width='2' opacity='0.6'/%3E%3Cline x1='12' y1='50' x2='88' y2='50' stroke='%23d9a63f' stroke-width='2' opacity='0.6'/%3E%3Cpolygon points='50,18 45,50 50,47 55,50' fill='%23e6c878'/%3E%3Cpolygon points='50,82 45,50 50,53 55,50' fill='%23f2e8d3' opacity='0.85'/%3E%3C/svg%3E">
<style>
:root {
  color-scheme: light dark;
  --sea-deep: #0d3b46;
  --sea-mid: #2f6f7a;
  --parchment: #f2e8d3;
  --parchment-2: #e9dbb8;
  --brass: #b6862f;
  --brass-bright: #d9a63f;
  --ink: #241f18;
  --coral: #c1573f;

  --bg: var(--parchment);
  --bg-panel: var(--parchment-2);
  --text: var(--ink);
  --text-dim: #5a5142;
  --accent: var(--sea-mid);
  --accent-strong: var(--sea-deep);
  --line: #c9b489;
  --shadow: 0 1px 0 rgba(36,31,24,0.08);
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0b2027; --bg-panel: #0f2c34; --text: #ecdfc2; --text-dim: #a99a78;
    --accent: #6fb3ad; --accent-strong: #d9a63f; --line: #2a4750;
    --shadow: 0 1px 0 rgba(0,0,0,0.4);
  }
}
:root[data-theme="dark"] {
  --bg: #0b2027; --bg-panel: #0f2c34; --text: #ecdfc2; --text-dim: #a99a78;
  --accent: #6fb3ad; --accent-strong: #d9a63f; --line: #2a4750;
  --shadow: 0 1px 0 rgba(0,0,0,0.4);
}
:root[data-theme="light"] {
  --bg: var(--parchment); --bg-panel: var(--parchment-2); --text: var(--ink); --text-dim: #5a5142;
  --accent: var(--sea-mid); --accent-strong: var(--sea-deep); --line: #c9b489;
  --shadow: 0 1px 0 rgba(36,31,24,0.08);
}
* { box-sizing: border-box; }
body {
  margin: 0; min-height: 100vh;
  background: radial-gradient(ellipse 900px 500px at 50% -10%, color-mix(in srgb, var(--accent) 14%, transparent), transparent), var(--bg);
  color: var(--text);
  font-family: Georgia, "Iowan Old Style", "Palatino Linotype", "Book Antiqua", serif;
  display: flex; justify-content: center; padding: 32px 16px 0;
}
.log { width: 100%; max-width: 640px; display: flex; flex-direction: column; min-height: calc(100vh - 32px); }
.cover { background: var(--accent-strong); border-radius: 14px 14px 0 0; padding: 22px 24px 26px; color: #f2e8d3; position: relative; overflow: hidden; box-shadow: var(--shadow); }
.cover::before { content: ""; position: absolute; inset: 0; background: repeating-linear-gradient(115deg, rgba(255,255,255,0.035) 0 2px, transparent 2px 26px); pointer-events: none; }
.cover-row { display: flex; align-items: center; gap: 16px; position: relative; }
.compass { flex: none; width: 56px; height: 56px; }
.cover-text .eyebrow { font-family: "JetBrains Mono", "Consolas", "SFMono-Regular", Menlo, monospace; font-size: 10.5px; letter-spacing: .16em; text-transform: uppercase; color: var(--brass-bright); margin: 0 0 4px; }
.cover-text h1 { margin: 0; font-size: 24px; line-height: 1.15; text-wrap: balance; font-weight: 700; letter-spacing: .01em; display: inline-flex; align-items: center; gap: 10px; }
.age-badge { flex: none; display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 21px; font-size: 11.5px; font-weight: 700; font-family: "JetBrains Mono", Consolas, Menlo, monospace; letter-spacing: 0; border: 1.5px solid #f2e8d3; border-radius: 4px; color: #f2e8d3; background: rgba(255,255,255,0.14); }
.cover-text .subtitle { margin: 3px 0 0; font-size: 11.5px; font-style: italic; color: #f2e8d3; opacity: .85; }
.cover-text p { margin: 6px 0 0; font-size: 13.5px; color: #cfe0dd; max-width: 46ch; }
.status { margin-top: 16px; display: flex; align-items: center; gap: 8px; font-family: "JetBrains Mono", Consolas, Menlo, monospace; font-size: 11px; color: #cfe0dd; letter-spacing: .04em; }
.status .dot { width: 7px; height: 7px; border-radius: 50%; background: #d9a63f; box-shadow: 0 0 0 3px rgba(217,166,63,0.22); transition: background .3s; }
.pages { flex: 1; background: var(--bg-panel); border-left: 1px solid var(--line); border-right: 1px solid var(--line); padding: 22px 22px 8px; display: flex; flex-direction: column; gap: 20px; overflow-y: auto; }
.entry { max-width: 88%; display: flex; flex-direction: column; gap: 5px; }
.entry.captain { align-self: flex-start; }
.entry.kid { align-self: flex-end; align-items: flex-end; }
.stamp { display: inline-flex; align-items: baseline; gap: 7px; font-family: "JetBrains Mono", Consolas, Menlo, monospace; font-size: 10.5px; letter-spacing: .12em; text-transform: uppercase; color: var(--text-dim); }
.entry.captain .stamp { color: var(--accent-strong); }
.entry.kid .stamp { color: var(--coral); }
.stamp .time { color: var(--text-dim); letter-spacing: 0; text-transform: none; opacity: .75; }
.bubble { font-size: 15.5px; line-height: 1.55; padding: 12px 15px; border-radius: 3px; position: relative; white-space: pre-wrap; }
.entry.captain .bubble { background: color-mix(in srgb, var(--accent-strong) 9%, var(--bg-panel)); border-left: 3px solid var(--accent-strong); }
.entry.kid .bubble { background: color-mix(in srgb, var(--coral) 12%, var(--bg-panel)); border-right: 3px solid var(--coral); text-align: left; }
.sources { font-family: "JetBrains Mono", Consolas, Menlo, monospace; font-size: 10px; color: var(--text-dim); letter-spacing: .02em; padding-left: 15px; }
.typing { align-self: flex-start; display: flex; gap: 5px; padding: 12px 15px; align-items: center; }
.typing span { width: 6px; height: 6px; border-radius: 50%; background: var(--accent-strong); opacity: .55; animation: bob 1.1s ease-in-out infinite; }
.typing span:nth-child(2) { animation-delay: .15s; }
.typing span:nth-child(3) { animation-delay: .3s; }
@keyframes bob { 0%, 100% { transform: translateY(0); opacity: .35; } 50% { transform: translateY(-4px); opacity: .9; } }
@media (prefers-reduced-motion: reduce) { .typing span { animation: none; opacity: .6; } }
.write { background: var(--bg-panel); border: 1px solid var(--line); border-top: 1px dashed var(--line); border-radius: 0 0 14px 14px; padding: 14px 16px 16px; }
.write-row { display: flex; gap: 10px; align-items: center; }
.write input { flex: 1; font-family: Georgia, "Iowan Old Style", serif; font-size: 15px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 11px 13px; color: var(--text); }
.write input::placeholder { color: var(--text-dim); opacity: .8; }
.write input:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.write input:disabled { opacity: .6; }
.send { flex: none; width: 44px; height: 44px; border-radius: 50%; border: 2px solid var(--brass); background: radial-gradient(circle at 35% 30%, var(--brass-bright), var(--brass) 70%); color: #2a1c08; font-size: 17px; cursor: pointer; display: grid; place-items: center; box-shadow: 0 2px 4px rgba(0,0,0,0.25); }
.send:hover { filter: brightness(1.06); }
.send:disabled { opacity: .6; cursor: default; }
.send:focus-visible { outline: 2px solid var(--accent-strong); outline-offset: 2px; }
.hint { margin: 9px 2px 0; font-size: 11px; color: var(--text-dim); font-family: "JetBrains Mono", Consolas, Menlo, monospace; letter-spacing: .02em; }
.waves { height: 30px; margin-top: 10px; overflow: hidden; opacity: .5; }
.waves svg { width: 200%; height: 100%; animation: drift 14s linear infinite; }
@media (prefers-reduced-motion: reduce) { .waves svg { animation: none; } }
@keyframes drift { from { transform: translateX(0); } to { transform: translateX(-50%); } }
@media (max-width: 460px) { .cover-text h1 { font-size: 20px; } .entry { max-width: 94%; } }
</style>
</head>
<body>
<div class="log">
  <header class="cover">
    <div class="cover-row">
      <svg class="compass" viewBox="0 0 100 100" role="img" aria-label="Компас">
        <circle cx="50" cy="50" r="46" fill="none" stroke="#d9a63f" stroke-width="2"/>
        <circle cx="50" cy="50" r="3" fill="#d9a63f"/>
        <line x1="50" y1="8" x2="50" y2="92" stroke="#d9a63f" stroke-width="1" opacity="0.6"/>
        <line x1="8" y1="50" x2="92" y2="50" stroke="#d9a63f" stroke-width="1" opacity="0.6"/>
        <polygon points="50,14 44,50 50,46 56,50" fill="#e6c878"/>
        <polygon points="50,86 44,50 50,54 56,50" fill="#f2e8d3" opacity="0.85"/>
        <text x="50" y="11" text-anchor="middle" font-size="9" fill="#f2e8d3" font-family="Georgia, serif">N</text>
      </svg>
      <div class="cover-text">
        <p class="eyebrow">Судовой журнал · частный рейс</p>
        <h1>Старый морской волк <span class="age-badge" aria-label="Возрастная маркировка 12+">12+</span></h1>
        <p class="subtitle">По мотивам приключений капитана Блада</p>
        <p>Байки, факты и советы бывалого моряка — про тёплые моря, китов, затонувшие клады и жизнь под парусом.</p>
      </div>
    </div>
    <div class="status"><span class="dot" id="status-dot"></span> <span id="status-text">проверяю связь...</span></div>
  </header>

  <main class="pages" id="pages">
    <div class="entry captain">
      <span class="stamp">Капитан <span class="time">на связи</span></span>
      <div class="bubble">ЗдорОво, юнга! Спрашивай что хочешь — про акул, клады, штормы, да хоть про китов.</div>
    </div>
  </main>

  <form class="write" id="chat-form">
    <div class="write-row">
      <input type="text" id="msg-input" placeholder="Спроси что-нибудь у капитана..." aria-label="Вопрос капитану" autocomplete="off">
      <button class="send" type="submit" id="send-btn" aria-label="Отправить">&#10148;</button>
    </div>
    <p class="hint">просто пиши и жди ответа капитана</p>
    <div class="waves">
      <svg viewBox="0 0 200 20" preserveAspectRatio="none">
        <path d="M0 10 Q 12.5 2 25 10 T 50 10 T 75 10 T 100 10 T 125 10 T 150 10 T 175 10 T 200 10" fill="none" stroke="currentColor" style="color:var(--accent)" stroke-width="1.4"/>
      </svg>
    </div>
  </form>
</div>

<script>
var SHOW_SOURCES = $showSourcesFlag;
var HISTORY_MAX_PAIRS = $VPS_HISTORY_MAX_PAIRS;
var chatHistory = [];
var pages = document.getElementById("pages");
var form = document.getElementById("chat-form");
var input = document.getElementById("msg-input");
var sendBtn = document.getElementById("send-btn");
var statusDot = document.getElementById("status-dot");
var statusText = document.getElementById("status-text");

function nowLabel() {
  var d = new Date();
  var hh = String(d.getHours()).padStart(2, "0");
  var mm = String(d.getMinutes()).padStart(2, "0");
  return hh + ":" + mm;
}

function addEntry(role, text, meta) {
  var entry = document.createElement("div");
  entry.className = "entry " + role;
  var stamp = document.createElement("span");
  stamp.className = "stamp";
  if (role === "captain") {
    stamp.innerHTML = "Капитан " + '<span class="time">' + nowLabel() + "</span>";
  } else {
    stamp.innerHTML = '<span class="time">' + nowLabel() + "</span> Ты";
  }
  entry.appendChild(stamp);
  var bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.textContent = text;
  entry.appendChild(bubble);
  if (meta) {
    var src = document.createElement("div");
    src.className = "sources";
    src.textContent = meta;
    entry.appendChild(src);
  }
  pages.appendChild(entry);
  pages.scrollTop = pages.scrollHeight;
  return entry;
}

function showTyping() {
  var t = document.createElement("div");
  t.className = "typing";
  t.id = "typing-indicator";
  t.innerHTML = "<span></span><span></span><span></span>";
  pages.appendChild(t);
  pages.scrollTop = pages.scrollHeight;
}

function hideTyping() {
  var t = document.getElementById("typing-indicator");
  if (t) t.remove();
}

form.addEventListener("submit", function (e) {
  e.preventDefault();
  var text = input.value.trim();
  if (!text) return;
  addEntry("kid", text);
  input.value = "";
  input.disabled = true;
  sendBtn.disabled = true;
  showTyping();

  fetch("/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message: text, history: chatHistory })
  }).then(function (res) {
    return res.json().then(function (data) { return { status: res.status, data: data }; });
  }).then(function (result) {
    hideTyping();
    if (result.status === 200) {
      var meta = null;
      if (SHOW_SOURCES && result.data.sources && result.data.sources.length > 0) {
        var seen = {};
        var titles = [];
        result.data.sources.forEach(function (s) {
          if (!seen[s.title]) { seen[s.title] = true; titles.push(s.title); }
        });
        meta = "из журнала: " + titles.join(", ");
      }
      var answerText = result.data.answer || "...";
      addEntry("captain", answerText, meta);
      chatHistory.push({ role: "user", text: text });
      chatHistory.push({ role: "captain", text: answerText });
      chatHistory = chatHistory.slice(-2 * HISTORY_MAX_PAIRS);
    } else if (result.status === 429) {
      addEntry("captain", "Слишком много вопросов подряд, юнга! Дай мне отдышаться полминутки и спроси снова.");
    } else if (result.status === 400) {
      addEntry("captain", "Вопрос длинноват даже для старого морского волка — сократи и спроси ещё раз.");
    } else {
      addEntry("captain", "Шторм на линии... попробуй ещё раз через минутку.");
    }
  }).catch(function () {
    hideTyping();
    addEntry("captain", "Не докричаться до корабля — проверь соединение и попробуй снова.");
  }).finally(function () {
    input.disabled = false;
    sendBtn.disabled = false;
    input.focus();
  });
});

fetch("/health").then(function (r) { return r.json(); }).then(function (d) {
  var ok = d.status === "ok";
  statusDot.style.background = ok ? "#7fd99a" : "#e2725b";
  statusText.textContent = ok ? "на связи" : "капитан прилёг отдохнуть";
}).catch(function () {
  statusDot.style.background = "#e2725b";
  statusText.textContent = "нет связи с кораблём";
});

input.focus();
</script>
</body>
</html>
""".trimIndent()
}

// =====================================================================
// Модель данных
// =====================================================================

/**
 * Параметры генерации ответа приватного сервиса.
 *
 * @param model       имя модели Ollama, используемой для ответов через `/chat`
 * @param temperature температура генерации
 * @param numCtx      окно контекста (Ollama `num_ctx`)
 * @param numPredict  потолок токенов ответа (Ollama `num_predict`)
 */
private data class VpsGenConfig(val model: String, val temperature: Double, val numCtx: Int, val numPredict: Int)

/**
 * Одна реплика истории чата, присылаемая клиентом вместе с новым сообщением — сервер сам
 * не хранит сессии (см. [PrivateLlmServer.handleChat]), историю ведёт браузер (JS) и шлёт
 * её целиком на каждый запрос; сервер лишь обрезает до последних [VPS_HISTORY_MAX_PAIRS] пар.
 *
 * @param role "user" (реплика мальчика) или "captain" (прошлый ответ капитана)
 * @param text текст реплики
 */
private data class ChatTurn(val role: String, val text: String)

/** Результат одного HTTP-вызова `/chat` с клиентской стороны. */
private data class ChatHttpResult(val code: Int, val body: String, val elapsedMs: Long, val error: String? = null)

// =====================================================================
// Rate limiter
// =====================================================================

/**
 * Простой sliding-window рейт-лимитер по IP клиента (in-memory, без внешних зависимостей).
 *
 * Единая блокировка на все IP — трафик сервиса заведомо мал (приватный/учебный сценарий,
 * не публичный масс-сервис), усложнять до per-key блокировок нет смысла.
 *
 * @param maxRequests максимум запросов в окне [windowMs] на один IP
 * @param windowMs    размер скользящего окна в миллисекундах
 */
private class RateLimiter(
    private val maxRequests: Int = VPS_RATE_LIMIT_MAX,
    private val windowMs: Long = VPS_RATE_LIMIT_WINDOW_MS
) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /**
     * Регистрирует запрос от [clientIp] и проверяет, не превышен ли лимит.
     *
     * @param clientIp IP-адрес клиента
     * @return true — запрос разрешён и уже учтён; false — лимит превышен, запрос НЕ учтён
     */
    @Synchronized
    fun allow(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        val deque = hits.getOrPut(clientIp) { ArrayDeque() }
        while (deque.isNotEmpty() && now - deque.first() > windowMs) deque.removeFirst()
        if (deque.size >= maxRequests) return false
        deque.addLast(now)
        return true
    }
}

// =====================================================================
// HTTP-сервер
// =====================================================================

/**
 * HTTP-обёртка над локальной Ollama — приватный RAG-сервис с ограничением доступа
 * (HTTP Basic Auth — логин/пароль), rate limit и ограничением на длину входящего запроса.
 *
 * Ollama остаётся забиндена на `127.0.0.1` (дефолт) — наружу торчит только этот сервер,
 * который сам решает, пускать ли запрос к модели дальше. Это и делает сервис «приватным»:
 * без верных логина/пароля и в пределах лимита запросов до модели вообще не доходит.
 *
 * @param port        порт, на котором сервис слушает `0.0.0.0`
 * @param login       ожидаемый логин HTTP Basic Auth
 * @param password    ожидаемый пароль HTTP Basic Auth
 * @param genConfig      параметры генерации ответа (модель/температура/контекст)
 * @param rateLimiter    лимитер запросов по IP клиента
 * @param showSourcesInUi показывать ли строку "из журнала: ..." под ответом в HTML-морде —
 *                        удобно локально для дебага retrieval, но лишнее/пугающее в проде
 *                        (см. [ConfigKeys.SHOW_SOURCES_IN_UI])
 */
private class PrivateLlmServer(
    private val port: Int,
    private val login: String,
    private val password: String,
    private val genConfig: VpsGenConfig,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val showSourcesInUi: Boolean = true
) {
    private var httpServer: HttpServer? = null

    /**
     * Флаг "сервис сейчас генерирует ответ". VPS — 1 vCPU без GPU: параллельно тянуть
     * две генерации нечем, они бы просто последовательно queue'ились внутри Ollama.
     * Вместо молчаливого многоминутного ожидания — второй одновременный вопрос сразу
     * получает [VPS_BUSY_ANSWER] (см. [handleChat]).
     */
    private val busy = AtomicBoolean(false)

    /**
     * Флаг "модель сейчас прогревается фоновым запросом" — см. [warmUpModelAsync] и
     * [handleChat]. Отдельно от [busy]: прогрев не связан с конкретным пользовательским
     * вопросом, гарантирует, что при нескольких холодных запросах подряд фоновый прогрев
     * запускается только один раз, а не по разу на каждый.
     */
    private val warming = AtomicBoolean(false)

    /** Поднимает HTTP-сервер и начинает слушать [port] на всех интерфейсах. */
    fun start() {
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
        server.executor = Executors.newFixedThreadPool(VPS_SERVER_THREADS)

        fun sharedAuthenticator() = object : BasicAuthenticator("private-llm") {
            // .trim() — пароль обычно копируют из мессенджера на телефоне, откуда легко
            // прилипает случайный пробел/перенос строки в начале или конце; без этого
            // такой ввод молча не проходит Basic Auth без внятной причины для пользователя.
            override fun checkCredentials(user: String, pwd: String): Boolean =
                user.trim() == login && pwd.trim() == password
        }

        val chatContext = server.createContext("/chat", ::handleChat)
        chatContext.authenticator = sharedAuthenticator()
        val indexContext = server.createContext("/", ::handleIndex)
        indexContext.authenticator = sharedAuthenticator()
        server.createContext("/health", ::handleHealth)
        server.start()
        httpServer = server
        printlnColumn(
            "[OK] Приватный LLM-сервис запущен на 0.0.0.0:$port " +
                "(модель ${genConfig.model}, numCtx=${genConfig.numCtx}, numPredict=${genConfig.numPredict})"
        )
        // Прогрев модели сразу при старте сервиса (после рестарта screen-сессии Ollama
        // "забывает" модель из памяти) — к моменту первого реального вопроса от юнги она
        // с большей вероятностью уже загружена, а не только по факту первого запроса.
        warmUpModelAsync()
    }

    /**
     * Фоново загружает [VpsGenConfig.model] в память Ollama минимальным запросом, не
     * блокируя вызывающий поток. Гарантирует единственный одновременный прогрев через
     * [warming] — повторные вызовы, пока прогрев уже идёт, ничего не делают.
     */
    private fun warmUpModelAsync() {
        if (!warming.compareAndSet(false, true)) return
        Thread {
            try {
                OllamaClient(genConfig.model).generate("Привет", numPredict = 1, numCtx = genConfig.numCtx)
            } finally {
                warming.set(false)
            }
        }.start()
    }

    /** Останавливает сервер немедленно (без задержки на завершение активных соединений). */
    fun stop() {
        httpServer?.stop(0)
    }

    /**
     * Обрабатывает `POST /chat`: rate limit → длина запроса → RAG-ответ.
     *
     * HTTP Basic Auth (логин/пароль) проверяется ДО вызова этого метода — сервером JDK
     * через [BasicAuthenticator], привязанный к контексту `/chat` в [start]; неверные
     * credentials до этого кода вообще не доходят (401 отдаёт сам JDK-сервер).
     */
    private fun handleChat(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                sendJson(exchange, 405, JSONObject().put("error", "используйте POST"))
                return
            }

            val clientIp = exchange.remoteAddress.address.hostAddress
            if (!rateLimiter.allow(clientIp)) {
                sendJson(
                    exchange, 429,
                    JSONObject().put(
                        "error",
                        "превышен лимит запросов ($VPS_RATE_LIMIT_MAX/${VPS_RATE_LIMIT_WINDOW_MS / 1000}с), попробуйте позже"
                    )
                )
                return
            }

            val rawBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            val (message, history) = try {
                val bodyJson = JSONObject(rawBody)
                val msg = bodyJson.optString("message", "")
                val historyArray = bodyJson.optJSONArray("history")
                val parsedHistory = historyArray?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val role = o.optString("role", "")
                        val text = o.optString("text", "")
                        if (role.isBlank() || text.isBlank()) null else ChatTurn(role, text)
                    }
                } ?: emptyList()
                msg to parsedHistory.takeLast(VPS_HISTORY_MAX_PAIRS * 2)
            } catch (e: Exception) {
                sendJson(exchange, 400, JSONObject().put("error", "некорректный JSON: ${e.message}"))
                return
            }

            if (message.isBlank()) {
                sendJson(exchange, 400, JSONObject().put("error", "поле message пустое"))
                return
            }
            if (message.length > VPS_MAX_MESSAGE_CHARS) {
                sendJson(
                    exchange, 400,
                    JSONObject().put(
                        "error",
                        "запрос слишком длинный (${message.length} символов), максимум $VPS_MAX_MESSAGE_CHARS"
                    )
                )
                return
            }

            if (isIdentityQuestion(message)) {
                sendJson(
                    exchange, 200,
                    JSONObject().put("answer", VPS_IDENTITY_ANSWER).put("sources", JSONArray()).put("elapsedMs", 0)
                )
                return
            }

            // Модель ещё не в памяти Ollama — реальный ответ может занять несколько минут
            // (холодная загрузка весов с диска на слабом VPS без GPU). Вместо молчаливого
            // ожидания или путающего VPS_BUSY_ANSWER — честное предупреждение + фоновый
            // прогрев, чтобы следующий вопрос (через полминутки, как и просим) застал
            // модель уже загруженной.
            if (!OllamaClient.isModelLoaded(genConfig.model)) {
                warmUpModelAsync()
                sendJson(
                    exchange, 200,
                    JSONObject().put("answer", VPS_WARMING_ANSWERS.random()).put("sources", JSONArray()).put("elapsedMs", 0)
                )
                return
            }

            if (!busy.compareAndSet(false, true)) {
                sendJson(
                    exchange, 200,
                    JSONObject().put("answer", VPS_BUSY_ANSWER).put("sources", JSONArray()).put("elapsedMs", 0)
                )
                return
            }
            try {
                sendJson(exchange, 200, answerWithRag(message, history, genConfig))
            } finally {
                busy.set(false)
            }
        } catch (e: Exception) {
            sendJson(exchange, 500, JSONObject().put("error", e.message ?: "внутренняя ошибка сервиса"))
        } finally {
            exchange.close()
        }
    }

    /**
     * Обрабатывает `GET /` — отдаёт HTML-морду ([vpsIndexHtml]) поверх `/chat`.
     * Basic Auth проверяется тем же способом, что и для `/chat` (см. [start]) — один общий
     * `realm`, поэтому браузер, один раз авторизовавшись на этом источнике, сам подставляет
     * те же credentials в fetch-запросы к `/chat` без какого-либо JS на странице.
     */
    private fun handleIndex(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                sendJson(exchange, 405, JSONObject().put("error", "используйте GET"))
                return
            }
            if (exchange.requestURI.path != "/") {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val bytes = vpsIndexHtml(showSourcesInUi).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.flush()
        } finally {
            exchange.close()
        }
    }

    /** Обрабатывает `GET /health` — без авторизации, отражает доступность локальной Ollama. */
    private fun handleHealth(exchange: HttpExchange) {
        try {
            val ollamaOk = OllamaClient.isServerRunning()
            sendJson(
                exchange, if (ollamaOk) 200 else 503,
                JSONObject().put("status", if (ollamaOk) "ok" else "ollama_unreachable")
            )
        } finally {
            exchange.close()
        }
    }

    private fun sendJson(exchange: HttpExchange, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.flush()
    }
}

/**
 * Отвечает на вопрос через упрощённый RAG (без rewrite/rerank Дней 28-29 — каждый лишний
 * вызов модели добавляет реальные секунды на 1 vCPU при КАЖДОМ запросе): эмбеддинг вопроса
 * → поиск по [DocumentIndex] → ответ [VpsGenConfig.model] с retrieved-контекстом.
 *
 * `DocumentIndex` открывается заново на каждый вызов (а не держится одним долгоживущим
 * инстансом сервера) — специально, чтобы файл `domain_assistant.db`, обновлённый через
 * `!index-doc`/`!index-telegram` + автосинк ([syncDbToVps]), подхватывался сразу же на
 * следующем запросе, без перезапуска сервиса на VPS.
 *
 * @param question вопрос пользователя (уже проверен на пустоту/длину вызывающей стороной)
 * @param history  последние реплики диалога (уже обрезаны до [VPS_HISTORY_MAX_PAIRS] пар
 *                 вызывающей стороной, см. [PrivateLlmServer.handleChat]) — нужны, чтобы
 *                 короткие уточнения ("а кто написал?") не превращались в вопрос в вакууме
 * @param cfg      параметры генерации
 * @return JSON-объект с полями `answer`/`sources`/`elapsedMs`/`inputTokens`/`outputTokens`
 */
private fun answerWithRag(question: String, history: List<ChatTurn>, cfg: VpsGenConfig): JSONObject {
    val embClient = EmbeddingClient()
    val queryEmbedding = embClient.embed(question, EmbedTask.QUERY)

    val bookRelated = isBookRelatedQuestion(question, history)

    val index = DocumentIndex(VPS_DB_PATH)
    val (hits, bookHint) = try {
        val h = if (isSafetyRelatedQuestion(question)) {
            // Безопасность — полный скан по ВСЕЙ базе (не усечённый VPS_SEARCH_POOL), иначе
            // нужный чанк может просто не попасть в топ-N по общему скору (см. комментарий
            // у VPS_SAFETY_KEYWORDS — так и оказалось на живом тесте: топ-25 не содержал
            // правильный чанк вообще). Полный скан — та же по сути SQL-выборка, что и в
            // DocumentIndex.search, просто без урезания результата до пула заранее.
            val fullPool = index.searchHybrid(queryEmbedding, question, topK = VPS_FULL_POOL_SIZE, poolSize = VPS_FULL_POOL_SIZE)
                .filter { !isFictionSource(it.chunk.source) }
            val safetyHits = fullPool.filter { it.chunk.source == VPS_SAFETY_SOURCE }.take(VPS_TOPIC_TOP_K)
            val otherHits = fullPool.filter { it.chunk.source != VPS_SAFETY_SOURCE }
            (safetyHits + otherHits).take(VPS_TOPIC_TOP_K)
        } else if (isRiggingRelatedQuestion(question)) {
            // Тот же приём, что и для безопасности (см. VPS_RIGGING_KEYWORDS) — вопросы про
            // ветер/такелаж/курсы теряли нужный файл среди случайных чанков по общему скору.
            val fullPool = index.searchHybrid(queryEmbedding, question, topK = VPS_FULL_POOL_SIZE, poolSize = VPS_FULL_POOL_SIZE)
                .filter { !isFictionSource(it.chunk.source) }
            val riggingHits = fullPool.filter { it.chunk.source == VPS_RIGGING_SOURCE }.take(VPS_TOPIC_TOP_K)
            val otherHits = fullPool.filter { it.chunk.source != VPS_RIGGING_SOURCE }
            (riggingHits + otherHits).take(VPS_TOPIC_TOP_K)
        } else if (isSharkRelatedQuestion(question)) {
            // Тот же приём (см. VPS_SHARK_KEYWORDS) — уточняющие вопросы про конкретный вид
            // акулы легко теряли нужный чанк среди других видов в том же файле.
            val fullPool = index.searchHybrid(queryEmbedding, question, topK = VPS_FULL_POOL_SIZE, poolSize = VPS_FULL_POOL_SIZE)
                .filter { !isFictionSource(it.chunk.source) }
            val sharkHits = fullPool.filter { it.chunk.source in VPS_SHARK_SOURCES }.take(VPS_TOPIC_TOP_K)
            val otherHits = fullPool.filter { it.chunk.source !in VPS_SHARK_SOURCES }
            (sharkHits + otherHits).take(VPS_TOPIC_TOP_K)
        } else if (isWeaponsRelatedQuestion(question)) {
            // Тот же приём (см. VPS_WEAPONS_KEYWORDS) — вопрос про оружие пиратов вообще не
            // находил файл, модель выдумывала анахронизмы на пустом контексте. Буст теперь
            // по МНОЖЕСТВУ источников (см. VPS_WEAPONS_SOURCES) — оба файла конкурируют за
            // TOPIC_TOP_K слотов по своему скору, а не один жёстко занимает все слоты.
            val fullPool = index.searchHybrid(queryEmbedding, question, topK = VPS_FULL_POOL_SIZE, poolSize = VPS_FULL_POOL_SIZE)
                .filter { !isFictionSource(it.chunk.source) }
            val weaponsHits = fullPool.filter { it.chunk.source in VPS_WEAPONS_SOURCES }.take(VPS_TOPIC_TOP_K)
            val otherHits = fullPool.filter { it.chunk.source !in VPS_WEAPONS_SOURCES }
            (weaponsHits + otherHits).take(VPS_TOPIC_TOP_K)
        } else if (isLifeRelatedQuestion(question)) {
            // Тот же приём (см. VPS_LIFE_KEYWORDS) — новый файл про быт легко теряется в
            // общем пуле по тому же паттерну, что оружие и акулы.
            val fullPool = index.searchHybrid(queryEmbedding, question, topK = VPS_FULL_POOL_SIZE, poolSize = VPS_FULL_POOL_SIZE)
                .filter { !isFictionSource(it.chunk.source) }
            val lifeHits = fullPool.filter { it.chunk.source == VPS_LIFE_SOURCE }.take(VPS_TOPIC_TOP_K)
            val otherHits = fullPool.filter { it.chunk.source != VPS_LIFE_SOURCE }
            (lifeHits + otherHits).take(VPS_TOPIC_TOP_K)
        } else if (isHistoryRelatedQuestion(question)) {
            // Тот же приём (см. VPS_HISTORY_KEYWORDS) — вопросы про реальных пиратов (Морган
            // и др.) без этого gate'а вообще не находили файл, и модель путала реальных
            // персонажей с вымышленным капитаном Бладом.
            val fullPool = index.searchHybrid(queryEmbedding, question, topK = VPS_FULL_POOL_SIZE, poolSize = VPS_FULL_POOL_SIZE)
                .filter { !isFictionSource(it.chunk.source) }
            val historyHits = fullPool.filter { it.chunk.source == VPS_HISTORY_SOURCE }.take(VPS_TOPIC_TOP_K)
            val otherHits = fullPool.filter { it.chunk.source != VPS_HISTORY_SOURCE }
            (historyHits + otherHits).take(VPS_TOPIC_TOP_K)
        } else {
            // Раньше — чистый косинус (VPS_SEARCH_POOL=25 кандидатов); теперь гибрид (BM25 +
            // косинус через RRF, см. DocumentIndex.searchHybrid) — на живых тестах 2026-07-13
            // BM25 надёжно находил дословные совпадения термина, которые косинус ронял мимо
            // топ-25 целиком (например, "утиные лапки", "стена огня").
            index.searchHybrid(queryEmbedding, question, topK = VPS_SEARCH_POOL)
                .filter { !isFictionSource(it.chunk.source) }
                .take(VPS_TOP_K)
        }
        // Книга сознательно исключена из основного пула (см. VPS_FICTION_MARKER) — но по прямому
        // решению пользователя для вопросов про Блада подмешивается ОДНО короткое предложение
        // из реального текста книги как подсказка для модели, а не только общие знания модели
        // (3b на голых общих знаниях путала и выдумывала базовые факты сюжета).
        val hint = if (bookRelated) bookExcerptHint(index, queryEmbedding) else null
        h to hint
    } finally {
        index.close()
    }

    val contextBlock = if (hits.isEmpty())
        "(в базе фактов не нашлось ничего подходящего по этому вопросу)"
    else
        hits.withIndex().joinToString("\n\n") { (i, h) -> "[${i + 1}] ${h.chunk.text}" }

    val historyBlock = if (history.isEmpty()) "" else
        "История разговора:\n" +
            history.joinToString("\n") { turn -> "${if (turn.role == "user") "Юнга" else "Капитан"}: ${turn.text}" } +
            "\n\n"

    val bookHintBlock = if (bookHint == null) "" else
        "\n\nОтрывок из настоящего текста книги (это могут быть слова другого персонажа о " +
            "Бладе, а не факт от лица автора — учитывай, кто говорит; не цитируй дословно, " +
            "перескажи своими словами; не спойлери развязку): «$bookHint»"

    val prompt = "${historyBlock}Контекст:\n$contextBlock$bookHintBlock\n\nВопрос: $question"

    val system = buildString {
        append(VPS_ANSWER_SYSTEM_BASE)
        if (bookRelated) append("\n\n").append(VPS_BOOK_TEASER_RULE)
        if (isRiggingRelatedQuestion(question)) append("\n\n").append(VPS_RIGGING_ANCHOR_RULE)
    }

    val gen = generateGuarded(
        cfg.model, prompt, system = system,
        temperature = cfg.temperature, numPredict = cfg.numPredict, numCtx = cfg.numCtx
    )

    val sources = JSONArray()
    hits.forEach { h ->
        sources.put(JSONObject().put("chunkId", h.chunk.chunkId).put("title", h.chunk.title).put("score", h.score.toDouble()))
    }

    // Модель (3b) на живом тесте 2026-07-13 несколько раз подряд не называла то автора, то
    // название первой книги (не всегда оба сразу), несмотря на явное "ОБЯЗАТЕЛЬНО" в промпте
    // — инструкция теряется среди остальных правил VPS_BOOK_TEASER_RULE. Раз полагаться на
    // выполнение инструкции ненадёжно, факт подставляется детерминированно кодом — автор и
    // название проверяются НЕЗАВИСИМО, чтобы не дублировать то, что модель уже сказала сама.
    val hasBookAuthor = gen.text.contains("Сабатини", ignoreCase = true)
    val hasBookTitle = gen.text.contains("Одиссе", ignoreCase = true)
    val bookPrefix = if (!bookRelated) "" else when {
        !hasBookAuthor && !hasBookTitle -> "Это серия книг Рафаэля Сабатини, первая называется «Одиссея капитана Блада». "
        !hasBookAuthor -> "Автор этой книжной серии — Рафаэль Сабатини. "
        !hasBookTitle -> "Первая книга серии называется «Одиссея капитана Блада». "
        else -> ""
    }
    val answerText = if (gen.error != null) "Ошибка генерации: ${gen.error}" else bookPrefix + gen.text

    return JSONObject()
        .put("answer", answerText)
        .put("sources", sources)
        .put("elapsedMs", gen.elapsedMs)
        .put("inputTokens", gen.inputTokens)
        .put("outputTokens", gen.outputTokens)
}

// =====================================================================
// Клиентская часть (HTTP-запросы к серверу)
// =====================================================================

/** Отправляет один `POST /chat`-запрос на сервис (HTTP Basic Auth) и возвращает код/тело/время ответа. */
private fun postChat(baseUrl: String, login: String, password: String, message: String): ChatHttpResult {
    val start = System.currentTimeMillis()
    return try {
        val conn = URL("$baseUrl/chat").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        val basicAuth = java.util.Base64.getEncoder().encodeToString("$login:$password".toByteArray(Charsets.UTF_8))
        conn.setRequestProperty("Authorization", "Basic $basicAuth")
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 120_000
        val body = JSONObject().put("message", message).toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        ChatHttpResult(code, text, System.currentTimeMillis() - start)
    } catch (e: Exception) {
        ChatHttpResult(-1, "", System.currentTimeMillis() - start, e.message ?: "неизвестная ошибка сети")
    }
}

/** Одиночный чат-запрос из REPL — печатает ответ и найденные источники. */
private fun sendSingleChat(baseUrl: String, login: String, password: String, message: String) {
    val result = postChat(baseUrl, login, password, message)
    if (result.error != null) {
        println("  ОШИБКА сети (${result.elapsedMs} мс): ${result.error}")
        return
    }
    if (result.code != 200) {
        println("  HTTP ${result.code} (${result.elapsedMs} мс): ${result.body}")
        return
    }
    val json = JSONObject(result.body)
    printlnColumn("\n[Ответ] (${result.elapsedMs} мс): ${json.optString("answer")}")
    val sources = json.optJSONArray("sources")
    if (sources != null && sources.length() > 0) {
        val list = (0 until sources.length()).joinToString(", ") { i ->
            val s = sources.getJSONObject(i)
            val score = (s.optDouble("score") * 100).toInt() / 100.0
            "${s.optString("title")} (score=$score)"
        }
        println("  Источники: $list")
    }
}

/**
 * `!stability [N]` — проверка «стабильность при нескольких запросах»: N параллельных
 * запросов к сервису, отчёт по успеху/ошибке/латентности каждого.
 *
 * @param n количество одновременных запросов
 */
private fun runStabilityTest(baseUrl: String, login: String, password: String, n: Int) {
    println("\n=== !stability: $n параллельных запросов ===")
    val executor = Executors.newFixedThreadPool(n.coerceAtMost(20))
    val question = "Простой тестовый вопрос для проверки стабильности сервиса."
    val futures: List<Future<ChatHttpResult>> = (1..n).map {
        executor.submit<ChatHttpResult> { postChat(baseUrl, login, password, question) }
    }
    var success = 0
    var failed = 0
    futures.forEachIndexed { i, future ->
        val result = try {
            future.get(180, TimeUnit.SECONDS)
        } catch (e: Exception) {
            ChatHttpResult(-1, "", 0, e.message ?: "таймаут ожидания ответа")
        }
        if (result.code == 200) {
            success++
            println("  [#${i + 1}] OK, ${result.elapsedMs} мс")
        } else {
            failed++
            println("  [#${i + 1}] ОШИБКА HTTP ${result.code} (${result.elapsedMs} мс): ${result.error ?: result.body.take(150)}")
        }
    }
    executor.shutdown()
    println("Итог: успешно $success/$n, ошибок $failed/$n")
}

/** `!ratelimit` — шлёт запросы сверх лимита подряд, показывает появление HTTP 429. */
private fun runRateLimitTest(baseUrl: String, login: String, password: String) {
    val total = VPS_RATE_LIMIT_MAX + 3
    println("\n=== !ratelimit: $total запросов подряд (лимит — $VPS_RATE_LIMIT_MAX/${VPS_RATE_LIMIT_WINDOW_MS / 1000}с) ===")
    repeat(total) { i ->
        val result = postChat(baseUrl, login, password, "Пинг $i для проверки rate limit.")
        println("  [#${i + 1}] HTTP ${result.code} (${result.elapsedMs} мс)")
    }
}

/** `!maxcontext` — шлёт намеренно длинное сообщение, показывает отказ HTTP 400. */
private fun runMaxContextTest(baseUrl: String, login: String, password: String) {
    val targetLen = VPS_MAX_MESSAGE_CHARS + 500
    println("\n=== !maxcontext: сообщение длиной ~$targetLen символов (лимит — $VPS_MAX_MESSAGE_CHARS) ===")
    val oversized = "текст ".repeat(targetLen / 6 + 1)
    val result = postChat(baseUrl, login, password, oversized)
    println("  HTTP ${result.code} (${result.elapsedMs} мс): ${result.body}")
}

// =====================================================================
// Локальная индексация + автосинк на VPS
// =====================================================================

/**
 * Прогоняет локальную индексацию (переиспользует [optIndexDocument]/[optIndexTelegramExport]
 * из `Week6Day4OptimizLocLLM.kt` без дублирования логики — там же живёт весь чанкинг/шумовой
 * фильтр) и затем автоматически синхронизирует обновлённый `domain_assistant.db` на VPS —
 * для пользователя это один шаг: залил файл → индексация → «улетело» на VPS.
 *
 * @param config конфигурация с (опциональными) ключами синка на VPS, см. [syncDbToVps]
 * @param action конкретное действие индексации — вызов `optIndexDocument`/`optIndexTelegramExport`
 */
private fun runLocalIndexAndSync(config: Properties?, action: (DocumentIndex, EmbeddingClient) -> Unit) {
    val index = DocumentIndex(VPS_DB_PATH)
    val embClient = EmbeddingClient()
    try {
        action(index, embClient)
    } finally {
        index.close()
    }
    syncDbToVps(config)
}

/**
 * Копирует локальный `domain_assistant.db` на VPS через `scp`, используя хост/ключ/
 * удалённый путь из `config.properties` ([ConfigKeys.VPS_HOST]/[ConfigKeys.VPS_SSH_KEY_PATH]/
 * [ConfigKeys.VPS_REMOTE_DB_PATH]). Если ключи не заданы — синк молча пропускается с
 * понятным сообщением (индексация локально всё равно уже выполнена и не теряется).
 *
 * @param config конфигурация проекта (может быть null, если `config.properties` не найден)
 */
private fun syncDbToVps(config: Properties?) {
    val host = config?.getProperty(ConfigKeys.VPS_HOST)
    val keyPath = config?.getProperty(ConfigKeys.VPS_SSH_KEY_PATH)
    val remotePath = config?.getProperty(ConfigKeys.VPS_REMOTE_DB_PATH)
    if (host.isNullOrBlank() || keyPath.isNullOrBlank() || remotePath.isNullOrBlank()) {
        println(
            "  [Синк на VPS пропущен] не заданы ${ConfigKeys.VPS_HOST}/${ConfigKeys.VPS_SSH_KEY_PATH}/" +
                "${ConfigKeys.VPS_REMOTE_DB_PATH} в config.properties"
        )
        return
    }
    println("  Отправляю $VPS_DB_PATH на $host:$remotePath ...")
    val process = ProcessBuilder("scp", "-i", keyPath, VPS_DB_PATH, "$host:$remotePath")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
    val exitCode = process.waitFor()
    if (exitCode == 0) {
        println("  [OK] База синхронизирована на VPS.")
    } else {
        println("  [X] Синк не удался (код $exitCode): $output")
    }
}

// =====================================================================
// main() и режимы
// =====================================================================

/**
 * Week 6 День 30 — локальная LLM как приватный сервис.
 *
 * Два режима: `[1]` сервер — поднимает HTTP API поверх локальной Ollama (RAG по
 * `domain_assistant.db`, ограничение доступа по HTTP Basic Auth — логин/пароль, rate
 * limit, ограничение длины запроса) — предназначен для запуска на VPS; `[2]` клиент/админ-REPL — с любой
 * машины проверяет сетевой доступ к сервису, стабильность при нескольких запросах,
 * срабатывание ограничений, а также локально индексирует новые документы/Telegram-экспорты
 * с автосинком базы на VPS.
 */
/**
 * Точка входа.
 *
 * @param args `--server` запускает HTTP-сервис напрямую, без интерактивного меню и без
 *             ожидания Enter для остановки — для systemd (`ExecStart=... --server`), где
 *             у процесса нет управляющего терминала/stdin. Без аргументов — прежнее
 *             интерактивное меню (для локальной разработки/ручного запуска).
 */
fun main(args: Array<String>) {
    println("=".repeat(80))
    printlnColumn("=== Капитан Блад: приватный LLM-чат-сервис ===")
    println("=".repeat(80))
    println()

    val config = loadConfigOrNullWithMessage()

    if (args.contains("--server")) {
        runServerMode(config, blockOnEnter = false)
        return
    }

    while (true) {
        print("Режим — [1] сервер (HTTP API, запускать на VPS) / [2] клиент-админ (Enter) / [3] выход: ")
        val mode = readlnWithFlush()?.trim()
        if (mode == null) return // EOF (stdin закрыт) — иначе бесконечный busy-loop на continue

        when (mode) {
            "1" -> { runServerMode(config); return }
            "2", "" -> { runClientMode(config); return }
            "3" -> return
            else -> continue // нераспознанный ввод — переспросить, не завершать приложение
        }
    }
}

/**
 * Режим `[1]` — поднимает HTTP-сервис.
 *
 * @param blockOnEnter true (по умолчанию, интерактивный запуск) — держит сервис открытым до
 *                     нажатия Enter, затем останавливает; false (`--server`, systemd) — просто
 *                     запускает и возвращается: JVM остаётся жить за счёт non-daemon потоков
 *                     `HttpServer`/пула потоков, остановка — через `systemctl stop captainblood`
 */
private fun runServerMode(config: Properties?, blockOnEnter: Boolean = true) {
    val login = config?.getProperty(ConfigKeys.PRIVATE_LLM_LOGIN)
    val password = config?.getProperty(ConfigKeys.PRIVATE_LLM_PASSWORD)
    if (login.isNullOrBlank() || password.isNullOrBlank()) {
        println(
            "${ConfigKeys.PRIVATE_LLM_LOGIN}/${ConfigKeys.PRIVATE_LLM_PASSWORD} не заданы в config.properties — " +
                "сервис не может стартовать без логина и пароля."
        )
        return
    }
    if (!OllamaClient.isServerRunning()) {
        println("Сервер Ollama не отвечает на localhost:11434. Запустите: ollama serve")
        return
    }

    val showSourcesInUi = config?.getProperty(ConfigKeys.SHOW_SOURCES_IN_UI)?.toBooleanStrictOrNull() ?: true

    val genConfig = VpsGenConfig(VPS_MODEL, VPS_TEMPERATURE, VPS_NUM_CTX, VPS_NUM_PREDICT)
    val server = PrivateLlmServer(VPS_PORT, login, password, genConfig, showSourcesInUi = showSourcesInUi)
    try {
        server.start()
    } catch (e: Exception) {
        println("Не удалось запустить сервер на порту $VPS_PORT: ${e.message}")
        return
    }
    if (!blockOnEnter) {
        println("[OK] Сервис запущен и работает в фоне (systemd) - остановка через systemctl stop captainblood.")
        return
    }
    println("Нажмите Enter, чтобы остановить сервис...")
    readlnWithFlush()
    server.stop()
    println("Сервис остановлен.")
}

/** Печатает список
 *  команд REPL с кратким описанием каждой — перед каждым вводом (как в Week6Day4OptimizLocLLM.kt). */
private fun vpsPrintCommands() {
    println("Команды:")
    println("  <текст>                  — одиночный чат-запрос")
    println("  !stability [N]           — N параллельных запросов (по умолчанию $VPS_STABILITY_DEFAULT_N)")
    println("  !ratelimit               — проверка rate limit (шлёт запросы сверх лимита)")
    println("  !maxcontext              — проверка ограничения длины запроса (намеренно длинный текст)")
    println("  !sources                 — список источников в базе")
    println("  !index-doc <путь>        — индексация документа + автосинк базы на VPS")
    println("  !index-telegram <путь>   — индексация экспорта Telegram + автосинк базы на VPS")
    println("  exit / выход             — завершить")
}

/** Режим `[2]` — клиент/админ-REPL: чат, проверочные команды и локальная индексация с автосинком. */
private fun runClientMode(config: Properties?) {
    val baseUrl = VPS_CLIENT_BASE_URL
    println("Адрес сервиса: $baseUrl")

    val login = config?.getProperty(ConfigKeys.PRIVATE_LLM_LOGIN)?.takeIf { it.isNotBlank() }
        ?: run {
            print("${ConfigKeys.PRIVATE_LLM_LOGIN} не найден в config.properties, введите логин вручную: ")
            readlnWithFlush()?.trim() ?: ""
        }
    val password = config?.getProperty(ConfigKeys.PRIVATE_LLM_PASSWORD)?.takeIf { it.isNotBlank() }
        ?: run {
            print("${ConfigKeys.PRIVATE_LLM_PASSWORD} не найден в config.properties, введите пароль вручную: ")
            readlnWithFlush()?.trim() ?: ""
        }

    println("\n=== Клиент/админ-REPL ===")

    while (true) {
        vpsPrintCommands()
        print("> ")
        val input = readlnWithFlush()?.trim()
        if (input == null) break // EOF (stdin закрыт) — иначе бесконечный busy-loop на continue
        if (input.isEmpty()) continue
        if (input.lowercase() in setOf("exit", "quit", "выход")) break

        when {
            input.lowercase() == "!stability" || input.lowercase().startsWith("!stability ") -> {
                val n = input.removePrefix("!stability").trim().toIntOrNull() ?: VPS_STABILITY_DEFAULT_N
                runStabilityTest(baseUrl, login, password, n)
            }
            input.lowercase() == "!ratelimit" -> runRateLimitTest(baseUrl, login, password)
            input.lowercase() == "!maxcontext" -> runMaxContextTest(baseUrl, login, password)
            input.lowercase() == "!sources" -> {
                val index = DocumentIndex(VPS_DB_PATH)
                try { optPrintSources(index) } finally { index.close() }
            }
            input.lowercase().startsWith("!index-doc ") || input.lowercase().startsWith("!index-telegram ") -> {
                println(
                    "  Индексация нового сырого контента (документ/Telegram-экспорт) в этой сборке " +
                        "не перенесена — требует DocumentReader/TelegramChatReader и PDFBox/POI, что " +
                        "не нужно ни серверу, ни watchdog'у. Обновляйте корпус (adventures_sea.db) " +
                        "прежним инструментом в aiexperiment и синкайте базу вручную (scp) или через " +
                        "syncDbToVps ниже."
                )
            }
            else -> sendSingleChat(baseUrl, login, password, input)
        }
    }
}
