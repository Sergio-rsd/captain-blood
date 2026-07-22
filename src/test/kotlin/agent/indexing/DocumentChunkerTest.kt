package agent.indexing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocumentChunkerTest {

    @Test
    fun `FixedChunker разбивает текст на чанки заданного размера с перекрытием`() {
        val text = "a".repeat(1000)
        val chunks = FixedChunker.chunk(title = "Doc", source = "doc.txt", text = text, chunkSize = 400, overlap = 50)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.text.length <= 400 })
        assertEquals("fixed", chunks.first().strategy)
        // Перекрытие: конец первого чанка должен совпадать с началом второго
        val overlapFromFirst = chunks[0].text.takeLast(50)
        val overlapInSecond = chunks[1].text.take(50)
        assertEquals(overlapFromFirst, overlapInSecond)
    }

    @Test
    fun `FixedChunker пропускает пустой текст без падения`() {
        val chunks = FixedChunker.chunk(title = "Doc", source = "doc.txt", text = "", chunkSize = 400, overlap = 50)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `StructuralChunker разбивает по заголовкам Markdown, если они есть`() {
        val text = """
            # Заголовок 1
            Текст первой секции.

            ## Заголовок 2
            Текст второй секции.
        """.trimIndent()

        val chunks = StructuralChunker.chunk(title = "Doc", source = "doc.md", text = text)

        assertEquals(2, chunks.size)
        assertEquals("# Заголовок 1", chunks[0].section)
        assertEquals("## Заголовок 2", chunks[1].section)
        assertTrue(chunks.all { it.strategy == "structural" })
    }

    @Test
    fun `StructuralChunker без заголовков падает обратно на разбиение по абзацам`() {
        val text = "Первый абзац текста.\n\nВторой абзац текста.\n\nТретий абзац текста."

        val chunks = StructuralChunker.chunk(title = "Doc", source = "doc.md", text = text, maxChunkSize = 10_000)

        // Все абзацы умещаются в один maxChunkSize — должны собраться в один чанк
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.contains("Первый абзац"))
        assertTrue(chunks[0].text.contains("Третий абзац"))
    }

    @Test
    fun `StructuralChunker режет большую секцию по maxChunkSize`() {
        val bigSection = "b".repeat(3000)
        val text = "# Заголовок\n$bigSection"

        val chunks = StructuralChunker.chunk(title = "Doc", source = "doc.md", text = text, maxChunkSize = 1000)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 1000 })
    }
}
