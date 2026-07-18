package agent.indexing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Хранилище индекса документов: SQLite (основной) + JSON (образцы для отладки).
 *
 * Эмбеддинги сохраняются как BLOB в формате little-endian float32
 * (4 байта × 768 чисел = 3072 байта на чанк).
 * Метаданные (source, title, section, strategy) доступны для SQL-фильтрации.
 *
 * @param dbPath путь к файлу базы данных SQLite
 */
@Suppress("SqlNoDataSourceInspection", "SqlResolve")
class DocumentIndex(private val dbPath: String = "index.db") {

    private val db: Connection by lazy {
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS chunks (
                chunk_id   TEXT PRIMARY KEY,
                source     TEXT NOT NULL,
                title      TEXT NOT NULL,
                section    TEXT NOT NULL,
                strategy   TEXT NOT NULL,
                chunk_text TEXT NOT NULL,
                embedding  BLOB NOT NULL
            )
        """.trimIndent())
        // Лексический (BM25) индекс поверх того же текста — параллельно с embedding-поиском,
        // см. [searchBm25]/[searchHybrid]. Отдельная FTS5-таблица, не связанная с `chunks`
        // внешним ключом (SQLite FTS5 не поддерживает PRIMARY KEY) — синхронизация вручную
        // в [save] (удалить старую запись по chunk_id перед вставкой новой).
        conn.createStatement().execute("""
            CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
                chunk_id UNINDEXED,
                chunk_text
            )
        """.trimIndent())
        // Самопочинка для баз, наполненных до появления chunks_fts (например, adventures_sea.db
        // на VPS после редеплоя) — при расхождении числа строк пересобираем лексический индекс
        // прямо тут, один раз при открытии, без ручного запуска rebuildFtsIndex() из консоли.
        val chunksCount = conn.createStatement().executeQuery("SELECT COUNT(*) FROM chunks").getInt(1)
        val ftsCount = conn.createStatement().executeQuery("SELECT COUNT(*) FROM chunks_fts").getInt(1)
        if (chunksCount != ftsCount) {
            conn.createStatement().execute("DELETE FROM chunks_fts")
            conn.createStatement().execute(
                "INSERT INTO chunks_fts (chunk_id, chunk_text) SELECT chunk_id, chunk_text FROM chunks"
            )
        }
        conn
    }

    /**
     * Сохраняет чанк с эмбеддингом в базу данных.
     * При повторном запуске перезаписывает чанк с тем же chunk_id.
     *
     * @param chunk чанк с заполненным полем embedding
     */
    fun save(chunk: Chunk) {
        db.prepareStatement(
            "INSERT OR REPLACE INTO chunks " +
            "(chunk_id, source, title, section, strategy, chunk_text, embedding) " +
            "VALUES (?,?,?,?,?,?,?)"
        ).use {
            it.setString(1, chunk.chunkId)
            it.setString(2, chunk.source)
            it.setString(3, chunk.title)
            it.setString(4, chunk.section)
            it.setString(5, chunk.strategy)
            it.setString(6, chunk.text)
            it.setBytes(7, floatArrayToBlob(chunk.embedding))
            it.executeUpdate()
        }
        // FTS5 не умеет INSERT OR REPLACE по смыслу (нет PRIMARY KEY) — чистим старую запись
        // вручную, иначе при переиндексации одного и того же chunk_id в лексическом поиске
        // накопятся дубли-призраки со старым текстом.
        db.prepareStatement("DELETE FROM chunks_fts WHERE chunk_id = ?").use {
            it.setString(1, chunk.chunkId)
            it.executeUpdate()
        }
        db.prepareStatement("INSERT INTO chunks_fts (chunk_id, chunk_text) VALUES (?, ?)").use {
            it.setString(1, chunk.chunkId)
            it.setString(2, chunk.text)
            it.executeUpdate()
        }
    }

    /**
     * Полная пересборка лексического (FTS5) индекса из уже сохранённых чанков.
     *
     * Нужна для баз, наполненных до появления [chunks_fts] (все чанки уже в `chunks`,
     * но лексического индекса под них ещё нет) — разовая миграция, вызывать один раз
     * на существующей базе, дальше [save] поддерживает синхронизацию сама.
     *
     * @return количество чанков, попавших в лексический индекс
     */
    fun rebuildFtsIndex(): Int {
        db.createStatement().execute("DELETE FROM chunks_fts")
        db.createStatement().execute(
            "INSERT INTO chunks_fts (chunk_id, chunk_text) SELECT chunk_id, chunk_text FROM chunks"
        )
        return count()
    }

    /**
     * Сохраняет образцы чанков в JSON-файл для наглядного просмотра.
     *
     * Эмбеддинг в JSON сокращён до первых 8 чисел — для читаемости.
     *
     * @param chunks      список всех чанков
     * @param path        путь к выходному JSON-файлу
     * @param perStrategy сколько образцов на каждую стратегию
     */
    fun saveJson(chunks: List<Chunk>, path: String, perStrategy: Int = 5) {
        val arr = JSONArray()
        listOf("fixed", "structural").forEach { strategy ->
            chunks.filter { it.strategy == strategy }.take(perStrategy).forEach { c ->
                val embPreview = JSONArray()
                c.embedding.take(8).forEach { embPreview.put(it.toDouble()) }
                embPreview.put("... (${c.embedding.size} total)")
                arr.put(
                    JSONObject()
                        .put("chunk_id",       c.chunkId)
                        .put("source",         c.source)
                        .put("title",          c.title)
                        .put("section",        c.section)
                        .put("strategy",       c.strategy)
                        .put("text_length",    c.text.length)
                        .put("text_preview",   c.text.take(200))
                        .put("embedding_preview", embPreview)
                )
            }
        }
        File(path).writeText(arr.toString(2), Charsets.UTF_8)
    }

    /**
     * Возвращает общее число чанков в базе данных.
     */
    fun count(): Int {
        val rs = db.createStatement().executeQuery("SELECT COUNT(*) FROM chunks")
        return if (rs.next()) rs.getInt(1) else 0
    }

    /**
     * Возвращает размерность эмбеддингов, уже сохранённых в базе (по первому попавшемуся чанку).
     *
     * Нужна для защитной проверки перед индексацией/поиском: если модель эмбеддингов
     * не совпадает с той, которой была наполнена база (разные модели дают векторы разной
     * длины и несовместимой геометрии), результат сравнения будет бессмысленным или упадёт.
     *
     * @return размерность вектора (количество чисел) или null, если база пуста
     */
    fun sampleEmbeddingDim(): Int? {
        val rs = db.createStatement().executeQuery("SELECT embedding FROM chunks LIMIT 1")
        return if (rs.next()) rs.getBytes(1).size / 4 else null
    }

    /**
     * Возвращает список уникальных источников (имён файлов) в базе.
     */
    fun listSources(): List<String> {
        val result = mutableListOf<String>()
        val rs = db.createStatement().executeQuery(
            "SELECT DISTINCT source FROM chunks ORDER BY source"
        )
        while (rs.next()) result += rs.getString(1)
        return result
    }

    /**
     * Возвращает первые [limit] чанков по источнику и стратегии (без эмбеддинга).
     *
     * @param source   имя файла-источника (поле source в БД)
     * @param strategy "fixed" или "structural"
     * @param limit    максимальное количество чанков
     */
    fun peekChunks(source: String, strategy: String, limit: Int = 5): List<Chunk> {
        val result = mutableListOf<Chunk>()
        db.prepareStatement(
            "SELECT chunk_id, source, title, section, strategy, chunk_text " +
            "FROM chunks WHERE source = ? AND strategy = ? " +
            "ORDER BY rowid LIMIT ?"
        ).use { stmt ->
            stmt.setString(1, source)
            stmt.setString(2, strategy)
            stmt.setInt(3, limit)
            val rs = stmt.executeQuery()
            while (rs.next()) result += Chunk(
                chunkId  = rs.getString(1),
                source   = rs.getString(2),
                title    = rs.getString(3),
                section  = rs.getString(4),
                strategy = rs.getString(5),
                text     = rs.getString(6)
            )
        }
        return result
    }

    /**
     * Возвращает все чанки в базе (без эмбеддинга) — для пересчёта эмбеддингов
     * с новой моделью/префиксом без повторного чтения исходных документов.
     */
    fun allChunks(): List<Chunk> {
        val result = mutableListOf<Chunk>()
        val rs = db.createStatement().executeQuery(
            "SELECT chunk_id, source, title, section, strategy, chunk_text FROM chunks ORDER BY rowid"
        )
        while (rs.next()) result += Chunk(
            chunkId  = rs.getString(1),
            source   = rs.getString(2),
            title    = rs.getString(3),
            section  = rs.getString(4),
            strategy = rs.getString(5),
            text     = rs.getString(6)
        )
        return result
    }

    /**
     * Возвращает количество чанков по источнику и стратегии.
     */
    fun countBySource(source: String, strategy: String): Int {
        val rs = db.prepareStatement(
            "SELECT COUNT(*) FROM chunks WHERE source = ? AND strategy = ?"
        ).also { it.setString(1, source); it.setString(2, strategy) }.executeQuery()
        return if (rs.next()) rs.getInt(1) else 0
    }

    /**
     * Возвращает уже сохранённые чанки телеграм-тредов для канала — `chunk_id -> chunk_text`.
     *
     * Используется при повторной/расширенной индексации экспорта Telegram Desktop
     * ([TelegramChatReader]), чтобы отличить треды, уже проиндексированные БЕЗ ИЗМЕНЕНИЙ
     * (текст совпадает — можно пропустить LLM-судью шума и переэмбеддинг), от новых или
     * изменившихся (получивших новые ответы в цепочке).
     *
     * Учитывает ОБЕ стратегии треда — `telegram_thread` и `day_announcement`
     * (см. [TelegramChatReader.buildChunks]) — иначе уже проиндексированные объявления
     * считались бы «новыми» при каждой повторной индексации.
     *
     * @param channel имя канала/чата — поле `source` в БД
     * @return карта chunk_id -> текущий сохранённый текст треда
     */
    fun existingTelegramChunks(channel: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        db.prepareStatement(
            "SELECT chunk_id, chunk_text FROM chunks WHERE source = ? AND strategy IN ('telegram_thread', 'day_announcement')"
        ).use { stmt ->
            stmt.setString(1, channel)
            val rs = stmt.executeQuery()
            while (rs.next()) result[rs.getString(1)] = rs.getString(2)
        }
        return result
    }

    /**
     * Ищет топ-[topK] чанков по косинусному сходству с запросом.
     *
     * Векторы уже L2-нормализованы при индексации, поэтому косинусное сходство
     * равно скалярному произведению. Загружает все эмбеддинги в память (~3 МБ),
     * вычисляет dot product и возвращает отсортированный результат.
     *
     * @param queryEmbedding L2-нормализованный вектор запроса (768 чисел)
     * @param topK           количество возвращаемых чанков
     * @param strategy       фильтр стратегии: "fixed", "structural" или null (обе)
     * @return список [SearchResult], отсортированный по убыванию score
     */
    fun search(queryEmbedding: FloatArray, topK: Int = 5, strategy: String? = null): List<SearchResult> {
        val sql = buildString {
            append("SELECT chunk_id, source, title, section, strategy, chunk_text, embedding FROM chunks")
            if (strategy != null) append(" WHERE strategy = '$strategy'")
        }
        val results = mutableListOf<SearchResult>()
        val rs = db.createStatement().executeQuery(sql)
        while (rs.next()) {
            val embedding = blobToFloatArray(rs.getBytes(7))
            val score = dotProduct(queryEmbedding, embedding)
            results += SearchResult(
                chunk = Chunk(
                    chunkId  = rs.getString(1),
                    source   = rs.getString(2),
                    title    = rs.getString(3),
                    section  = rs.getString(4),
                    strategy = rs.getString(5),
                    text     = rs.getString(6),
                    embedding = embedding
                ),
                score = score
            )
        }
        return results.sortedByDescending { it.score }.take(topK)
    }

    /**
     * Возвращает чанки в окне [windowSize] соседей вокруг заданного чанка.
     *
     * Соседи выбираются по rowid из той же стратегии и источника,
     * что позволяет получить связный текстовый контекст для LLM.
     *
     * @param chunkId    id центрального чанка
     * @param windowSize количество соседей с каждой стороны (1 = пред + центр + след)
     * @return список чанков, отсортированных по rowid
     */
    fun getContext(chunkId: String, windowSize: Int = 1): List<Chunk> {
        val centerRs = db.prepareStatement(
            "SELECT rowid, source, strategy FROM chunks WHERE chunk_id = ?"
        ).also { it.setString(1, chunkId) }.executeQuery()
        if (!centerRs.next()) return emptyList()
        val centerRowid = centerRs.getLong(1)
        val source      = centerRs.getString(2)
        val strategy    = centerRs.getString(3)

        val result = mutableListOf<Chunk>()
        db.prepareStatement(
            "SELECT chunk_id, source, title, section, strategy, chunk_text " +
            "FROM chunks WHERE source = ? AND strategy = ? " +
            "AND rowid BETWEEN ? AND ? ORDER BY rowid"
        ).use { stmt ->
            stmt.setString(1, source)
            stmt.setString(2, strategy)
            stmt.setLong(3, centerRowid - windowSize)
            stmt.setLong(4, centerRowid + windowSize)
            val rs = stmt.executeQuery()
            while (rs.next()) result += Chunk(
                chunkId  = rs.getString(1),
                source   = rs.getString(2),
                title    = rs.getString(3),
                section  = rs.getString(4),
                strategy = rs.getString(5),
                text     = rs.getString(6)
            )
        }
        return result
    }

    /**
     * Ищет топ-[topK] чанков лексическим (BM25 через SQLite FTS5) поиском по запросу.
     *
     * В отличие от [search] (косинус по эмбеддингам, "похоже по смыслу"), находит точные/
     * близкие вхождения слов — надёжен ровно там, где эмбеддинг слаб: искомый термин
     * дословно есть в чанке, но семантически "теряется" среди тематически похожего текста
     * (см. память проекта: "стена огня", "катлас" — оба случая нашёл BM25 в живом тесте,
     * пропустил чистый embedding-поиск).
     *
     * @param queryText сырой текст вопроса пользователя (токенизируется и очищается внутри)
     * @param topK      количество возвращаемых чанков
     * @return список [SearchResult] по убыванию релевантности; score — это ранг-позиция
     *         (1.0 / (1 + позиция)), НЕ сопоставим напрямую со score из [search]
     */
    fun searchBm25(queryText: String, topK: Int = 5): List<SearchResult> {
        val ftsQuery = sanitizeFtsQuery(queryText)
        if (ftsQuery.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResult>()
        // bm25() — агрегатная функция FTS5, привязанная к rowid-у самой виртуальной таблицы;
        // через JOIN с алиасом (`bm25(f)`) SQLite её не резолвит ("no such column: f").
        // Рабочий паттерн — сначала ранжировать САМУ chunks_fts подзапросом, потом джойнить
        // наружу за метаданными.
        db.prepareStatement(
            "SELECT c.chunk_id, c.source, c.title, c.section, c.strategy, c.chunk_text " +
            "FROM (" +
            "  SELECT chunk_id, bm25(chunks_fts) AS rank FROM chunks_fts " +
            "  WHERE chunks_fts MATCH ? ORDER BY rank LIMIT ?" +
            ") f JOIN chunks c ON c.chunk_id = f.chunk_id " +
            "ORDER BY f.rank"
        ).use { stmt ->
            stmt.setString(1, ftsQuery)
            stmt.setInt(2, topK)
            val rs = stmt.executeQuery()
            var rank = 0
            while (rs.next()) {
                results += SearchResult(
                    chunk = Chunk(
                        chunkId  = rs.getString(1),
                        source   = rs.getString(2),
                        title    = rs.getString(3),
                        section  = rs.getString(4),
                        strategy = rs.getString(5),
                        text     = rs.getString(6)
                    ),
                    score = 1f / (1 + rank)
                )
                rank++
            }
        }
        return results
    }

    /**
     * Гибридный поиск: сливает [search] (эмбеддинги, "похоже по смыслу") и [searchBm25]
     * (точные слова) через Reciprocal Rank Fusion — каждый чанк получает
     * `1/(k+ранг_косинус) + 1/(k+ранг_bm25)`, отсутствие в одном из списков не штрафуется
     * до нуля, просто не даёт вклада от этого метода. RRF выбран за то, что не требует
     * подбора весов между разномасштабными score (косинус 0..1, bm25 — произвольная шкала).
     *
     * @param queryEmbedding L2-нормализованный вектор запроса — для [search]
     * @param queryText      сырой текст вопроса — для [searchBm25]
     * @param topK           сколько чанков вернуть в итоге
     * @param poolSize       сколько кандидатов брать из КАЖДОГО метода перед слиянием
     *                       (больше — точнее ранги на хвосте, дороже по времени)
     * @param rrfK           константа сглаживания RRF (60 — стандартное значение в литературе)
     * @param bm25Weight     множитель вклада BM25-ранга в итоговый скор относительно косинуса
     *                       (по умолчанию 4.0 — подобрано на батарее из 9 живых вопросов
     *                       2026-07-13: при весе 1.5 два кейса из девяти проигрывали ложному,
     *                       но уверенному косинус-хиту; на 4.0 все девять сошлись на верном
     *                       чанке на 1-м месте, остальные семь не сломались; выше не пробовал —
     *                       9/9 достаточно для текущей проверки, не оптимум)
     * @return топ-[topK] чанков по убыванию RRF-скора; score в результате — сам RRF-скор
     *         (маленькие числа порядка 0.01-0.03), НЕ сопоставим напрямую с косинусом или BM25
     */
    fun searchHybrid(
        queryEmbedding: FloatArray,
        queryText: String,
        topK: Int = 5,
        poolSize: Int = 30,
        rrfK: Int = 60,
        bm25Weight: Double = 4.0
    ): List<SearchResult> {
        val cosineHits = search(queryEmbedding, topK = poolSize)
        val bm25Hits = searchBm25(queryText, topK = poolSize)

        val byChunkId = mutableMapOf<String, Chunk>()
        val rrfScore = mutableMapOf<String, Double>()

        cosineHits.forEachIndexed { rank, hit ->
            byChunkId[hit.chunk.chunkId] = hit.chunk
            rrfScore.merge(hit.chunk.chunkId, 1.0 / (rrfK + rank + 1), Double::plus)
        }
        bm25Hits.forEachIndexed { rank, hit ->
            byChunkId.putIfAbsent(hit.chunk.chunkId, hit.chunk)
            rrfScore.merge(hit.chunk.chunkId, bm25Weight / (rrfK + rank + 1), Double::plus)
        }

        return rrfScore.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { (chunkId, score) -> SearchResult(chunk = byChunkId.getValue(chunkId), score = score.toFloat()) }
    }

    /** Превращает вопрос пользователя в безопасный FTS5-запрос: только словá от 3 символов
     *  через OR — иначе неизбежный AND по умолчанию проваливает почти любой реальный вопрос
     *  (в нём почти всегда есть слова, которых нет в целевом чанке).
     *
     *  Каждое слово ищется ПРЕФИКСОМ ([stripRussianEnding] + `*`), не точным совпадением —
     *  найдено на живом тесте 2026-07-13: FTS5 по умолчанию не учитывает русские падежные
     *  окончания ("стена" в вопросе не матчился на "стены" в тексте — разные строки для
     *  движка), из-за чего верный чанк тонул на 19-м месте вместо топ-3. Грубый (без
     *  лингвистики) отброс последних 1-2 символов у достаточно длинных слов покрывает
     *  большинство падежных окончаний ценой небольшого над-совпадения на коротких основах. */
    private fun sanitizeFtsQuery(text: String): String {
        val tokens = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .distinct()
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { "${stripRussianEnding(it)}*" }
    }

    private fun stripRussianEnding(token: String): String = when {
        token.length > 6 -> token.dropLast(2)
        token.length > 3 -> token.dropLast(1)
        else -> token
    }

    /** Закрывает соединение с базой данных. */
    fun close() = db.close()

    private fun floatArrayToBlob(arr: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        arr.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun blobToFloatArray(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.float }
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}

/**
 * Результат поиска по индексу: чанк документа и его косинусное сходство с запросом.
 *
 * @param chunk найденный чанк
 * @param score косинусное сходство с вектором запроса (0..1, чем выше — тем релевантнее)
 */
data class SearchResult(val chunk: Chunk, val score: Float)
