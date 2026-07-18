package agent.indexing

/**
 * Данные одного чанка документа с метаданными.
 *
 * @param chunkId   уникальный идентификатор чанка
 * @param source    имя исходного файла
 * @param title     заголовок документа (имя файла без расширения)
 * @param section   название секции: заголовок Markdown, "chunk_N" или "para_N"
 * @param strategy  стратегия чанкинга: "fixed" или "structural"
 * @param text      текст чанка
 * @param embedding вектор эмбеддинга (заполняется отдельно через EmbeddingClient)
 */
data class Chunk(
    val chunkId: String,
    val source: String,
    val title: String,
    val section: String,
    val strategy: String,
    val text: String,
    val embedding: FloatArray = floatArrayOf()
) {
    override fun equals(other: Any?) = other is Chunk && chunkId == other.chunkId
    override fun hashCode() = chunkId.hashCode()
}

private fun slugify(title: String): String = title
    .replace(Regex("[^a-zA-Zа-яА-Я0-9]"), "_")
    .replace(Regex("_+"), "_")
    .trim('_')
    .lowercase()

/**
 * Фиксированное разбиение текста на чанки заданного размера с перекрытием.
 *
 * Каждый чанк содержит [chunkSize] символов, соседние чанки перекрываются
 * на [overlap] символов чтобы не потерять контекст на границе.
 */
object FixedChunker {

    /**
     * Разбивает текст на чанки фиксированного размера.
     *
     * @param title     заголовок документа
     * @param source    имя исходного файла
     * @param text      исходный текст
     * @param chunkSize размер чанка в символах
     * @param overlap   перекрытие между соседними чанками в символах
     * @return список чанков со стратегией "fixed"
     */
    fun chunk(title: String, source: String, text: String,
              chunkSize: Int = 800, overlap: Int = 100): List<Chunk> {
        val slug = slugify(title)
        val step = chunkSize - overlap
        val chunks = mutableListOf<Chunk>()
        var idx = 0
        var n = 0
        while (idx < text.length) {
            val piece = text.substring(idx, minOf(idx + chunkSize, text.length)).trim()
            if (piece.isNotEmpty()) {
                chunks += Chunk(
                    chunkId  = "${slug}_fixed_$n",
                    source   = source,
                    title    = title,
                    section  = "chunk_$n",
                    strategy = "fixed",
                    text     = piece
                )
                n++
            }
            idx += step
        }
        return chunks
    }
}

/**
 * Структурное разбиение текста на чанки по смысловым границам.
 *
 * Режим 1 — заголовки Markdown (## / ###): для структурированных документов.
 * Режим 2 — абзацы (разделитель \n\n, fallback): для любого неструктурированного
 * текста: рассказов, должностных инструкций, корпоративных документов без разметки.
 *
 * @param maxChunkSize максимальный размер одного чанка в символах
 */
object StructuralChunker {

    private val HEADER_RE = Regex("""^#{1,3} .+""", RegexOption.MULTILINE)

    /**
     * Разбивает текст структурно: по заголовкам если есть, иначе по абзацам.
     *
     * @param title       заголовок документа
     * @param source      имя исходного файла
     * @param text        исходный текст
     * @param maxChunkSize максимальный размер чанка в символах
     * @return список чанков со стратегией "structural"
     */
    fun chunk(title: String, source: String, text: String, maxChunkSize: Int = 1200): List<Chunk> {
        val slug = slugify(title)
        val normalized = text.replace("\r\n", "\n")
        return if (HEADER_RE.containsMatchIn(normalized))
            chunkByHeaders(slug, title, source, normalized, maxChunkSize)
        else
            chunkByParagraphs(slug, title, source, normalized, maxChunkSize)
    }

    private fun chunkByHeaders(slug: String, title: String, source: String,
                                text: String, maxChunkSize: Int): List<Chunk> {
        val matches = HEADER_RE.findAll(text).toList()
        val result = mutableListOf<Chunk>()
        var n = 0

        // Преамбула до первого заголовка
        val prelude = text.substring(0, matches[0].range.first).trim()
        if (prelude.isNotEmpty()) {
            result += Chunk("${slug}_str_$n", source, title, "(intro)", "structural", prelude)
            n++
        }

        for (i in matches.indices) {
            val header = matches[i].value
            val start  = matches[i].range.last + 1
            val end    = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val content = text.substring(start, end).trim()
            if (content.isEmpty()) continue
            if (content.length <= maxChunkSize) {
                result += Chunk("${slug}_str_$n", source, title, header, "structural", content)
                n++
            } else {
                // Секция слишком большая — дополнительное фиксированное разбиение
                var idx = 0
                var sub = 0
                while (idx < content.length) {
                    val piece = content.substring(idx, minOf(idx + maxChunkSize, content.length)).trim()
                    if (piece.isNotEmpty()) {
                        result += Chunk("${slug}_str_$n", source, title, "$header ($sub)", "structural", piece)
                        n++; sub++
                    }
                    idx += maxChunkSize
                }
            }
        }
        return result
    }

    private fun chunkByParagraphs(slug: String, title: String, source: String,
                                   text: String, maxChunkSize: Int): List<Chunk> {
        val paragraphs = text.split(Regex("""\n\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<Chunk>()
        var n = 0
        val buffer = StringBuilder()

        for (para in paragraphs) {
            if (buffer.isNotEmpty() && buffer.length + para.length + 2 > maxChunkSize) {
                result += Chunk("${slug}_str_$n", source, title, "para_$n", "structural", buffer.toString().trim())
                n++
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(para)
        }
        if (buffer.isNotEmpty()) {
            result += Chunk("${slug}_str_$n", source, title, "para_$n", "structural", buffer.toString().trim())
        }
        return result
    }
}
