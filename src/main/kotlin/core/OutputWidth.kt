package core

/**
 * Утилиты форматирования вывода с контролем ширины колонки.
 * Используется для печати ответов LLM с заданной шириной строки.
 */

/**
 * Форматирует текст в колонку заданной ширины.
 *
 * @param text исходный текст для вывода
 * @param maxWidth максимальная ширина строки
 * @return текст с переносами строк
 */
fun formatAsColumn(text: String, maxWidth: Int = ConfigKeys.DEFAULT_COLUMN_WIDTH): String {
    return text
        .lineSequence()
        .flatMap { line -> wrapLine(line, maxWidth).lineSequence() }
        .joinToString("\n")
}

/**
 * Переносит одну строку по словам, сохраняя пустые строки.
 *
 * @param line исходная строка
 * @param maxWidth максимальная ширина строки
 * @return строка с переносами
 */
fun wrapLine(line: String, maxWidth: Int): String {
    if (line.length <= maxWidth) return line
    if (line.isBlank()) return line

    val result = StringBuilder()
    val words = line.trim().split(Regex("\\s+"))
    var currentLine = StringBuilder()

    for (word in words) {
        if (word.length > maxWidth) {
            if (currentLine.isNotEmpty()) {
                result.append(currentLine).append('\n')
                currentLine = StringBuilder()
            }
            var start = 0
            while (start < word.length) {
                val end = (start + maxWidth).coerceAtMost(word.length)
                result.append(word.substring(start, end))
                if (end < word.length) result.append('\n')
                start = end
            }
        } else if (currentLine.isEmpty()) {
            currentLine.append(word)
        } else if (currentLine.length + 1 + word.length <= maxWidth) {
            currentLine.append(' ').append(word)
        } else {
            result.append(currentLine).append('\n')
            currentLine = StringBuilder(word)
        }
    }

    if (currentLine.isNotEmpty()) {
        if (result.isNotEmpty() && result.last() != '\n') result.append('\n')
        result.append(currentLine)
    }

    return result.toString()
}

/**
 * Печатает текст в колонку заданной ширины.
 *
 * @param text исходный текст для вывода
 */
fun printlnColumn(text: String) {
    println(formatAsColumn(text))
}
