package agent.indexing

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileInputStream

/**
 * Чтение текста из документов разных форматов для индексации в RAG.
 *
 * Вынесено из `Week5Day1IndexDoc.kt` в общий модуль — эта логика нужна и точке
 * входа Дня 21 (пакетная индексация), и Дню 23 (`!index` — добавление документа
 * прямо во время работы RAG-REPL).
 */
object DocumentReader {

    /**
     * Читает содержимое файла в зависимости от расширения.
     *
     * Поддерживаемые форматы: .pdf, .docx, .doc, .md, .txt и любые текстовые файлы.
     *
     * @param path абсолютный или относительный путь к файлу
     * @return текстовое содержимое файла
     */
    fun readFile(path: String): String = when {
        path.endsWith(".pdf",  ignoreCase = true) -> extractPdf(path)
        path.endsWith(".docx", ignoreCase = true) -> extractDocx(path)
        path.endsWith(".doc",  ignoreCase = true) -> extractDoc(path)
        else -> {
            val bytes = File(path).readBytes()
            val utf8 = String(bytes, Charsets.UTF_8)
            // Если UTF-8 дал символы замены — файл в Windows-1251 (типично для русских .txt)
            if ('�' in utf8) String(bytes, charset("windows-1251")) else utf8
        }
    }

    /**
     * Извлекает текст из .docx-файла (Word 2007+) с помощью Apache POI.
     *
     * Читает текст из абзацев и таблиц — важно для договоров, где условия
     * часто оформлены в виде таблиц.
     *
     * @param path путь к .docx-файлу
     * @return извлечённый текст
     */
    private fun extractDocx(path: String): String {
        XWPFDocument(FileInputStream(path)).use { doc ->
            val sb = StringBuilder()
            doc.paragraphs.forEach { p -> if (p.text.isNotBlank()) sb.append(p.text).append("\n\n") }
            doc.tables.forEach { table ->
                table.rows.forEach { row ->
                    row.tableCells.forEach { cell ->
                        cell.paragraphs.forEach { p -> if (p.text.isNotBlank()) sb.append(p.text).append("\n\n") }
                    }
                }
            }
            val text = sb.toString().trim()
            println("    DOCX: ${doc.paragraphs.size} абзацев → ${text.length} символов")
            return text
        }
    }

    /**
     * Извлекает текст из .doc-файла (Word 97–2003) с помощью Apache POI.
     *
     * @param path путь к .doc-файлу
     * @return извлечённый текст
     */
    private fun extractDoc(path: String): String {
        HWPFDocument(FileInputStream(path)).use { doc ->
            val text = doc.range.text().replace("\r", "\n").trim()
            println("    DOC: ${text.length} символов")
            return text
        }
    }

    /**
     * Извлекает текст из PDF-файла с помощью Apache PDFBox.
     *
     * @param path путь к PDF-файлу
     * @return извлечённый текст со всех страниц
     */
    private fun extractPdf(path: String): String {
        val file = File(path)
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)
            println("    PDF: ${doc.numberOfPages} стр. → ${text.length} символов")
            return text
        }
    }
}
