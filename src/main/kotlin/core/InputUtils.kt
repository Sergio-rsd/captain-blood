package core

import client.LlmClient

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Утилиты для работы с пользовательским вводом.
 *
 * ВСЕ функции чтения используют [sharedReader] — единый BufferedReader
 * вокруг System.in. Это исключает проблемы с перекрестным буферизированием,
 * когда разные readlnOrNull() создают свои BufferedReader и «крадут» данные
 * друг у друга (особенно заметно в IntelliJ IDEA).
 */
private val sharedReader: BufferedReader =
    BufferedReader(InputStreamReader(System.`in`))

/**
 * Читает строку из разделяемого ридера с предварительным сбросом буфера вывода.
 *
 * @return введённая строка или null при EOF
 */
private fun readLineSafe(): String? {
    return try {
        sharedReader.readLine()
    } catch (_: Exception) {
        null
    }
}

/**
 * Читает строку с предварительным сбросом буфера вывода.
 */
fun readlnWithFlush(): String? {
    System.out.flush()
    return readLineSafe()
}

/**
 * Читает запрос от пользователя с возможностью указания значения по умолчанию.
 *
 * Поддерживает многострочный ввод: копируйте текст и вставляйте (Ctrl+V),
 * затем нажмите Enter на пустой строке для завершения.
 *
 * @param prompt текст приглашения для ввода
 * @param default значение по умолчанию, если ввод пустой
 * @return введенный пользователем текст или значение по умолчанию
 */
fun readQuery(prompt: String, default: String): String {
    print(prompt)
    System.out.flush()
    println("(для многострочного ввода — скопируйте текст и нажмите Enter на пустой строке для завершения)")
    val lines = mutableListOf<String>()
    while (true) {
        val line = readLineSafe()
        if (line == null) break // EOF (Ctrl+Z)
        val trimmed = line.trimEnd()
        if (lines.isEmpty() && trimmed.isEmpty()) {
            // Пустой первый ввод — считаем, что пользователь ещё не ввёл запрос
            println("Запрос не может быть пустым. Пожалуйста, введите ваш запрос:")
            continue
        }
        if (lines.isNotEmpty() && trimmed.isEmpty()) break // пустая строка завершает ввод
        lines.add(trimmed)
    }
    if (lines.isEmpty()) {
        println("Запрос не может быть пустым. Используется запрос по умолчанию.")
        return default
    }
    val query = lines.joinToString("\n")
    println()
    println("Ваш запрос (отформатирован по ширине ${ConfigKeys.DEFAULT_COLUMN_WIDTH}):")
    printlnColumn(query)
    println()
    return query
}

/**
 * Парсит yes/no ответ пользователя.
 */
fun parseBooleanChoice(input: String?): Boolean {
    return when (input?.trim()?.lowercase()) {
        "yes", "y", "да", "д" -> true
        else -> false
    }
}

/**
 * Выбор LLM из списка доступных моделей.
 */
fun selectLLM(defaultChoice: String = "2"): String {
    println("Доступные LLM:")
    LlmClient.AVAILABLE_MODELS.forEachIndexed { index, model ->
        val priceInfo = "[вход: ${"%.2f".format(model.inputPricePerMillion).replace(',','.')}₽/1M, выход: ${"%.2f".format(model.outputPricePerMillion).replace(',','.')}₽/1M]"
        println("${index + 1}. ${model.displayName} $priceInfo")
    }
    println()
    
    print("Выберите LLM (номер или название, по умолчанию deepseek): ")
    System.out.flush()
    val input = readLineSafe()?.trim()
    
    if (input.isNullOrEmpty()) {
        val defaultModel = LlmClient.AVAILABLE_MODELS.find { it.choice == defaultChoice }
            ?: LlmClient.AVAILABLE_MODELS[1]
        println("Используется модель по умолчанию: ${defaultModel.displayName}")
        return defaultModel.displayName
    }
    
    val byNumber = LlmClient.AVAILABLE_MODELS.find { it.choice == input }
    if (byNumber != null) {
        return byNumber.displayName
    }
    
    val byName = LlmClient.AVAILABLE_MODELS.find {
        it.displayName.equals(input, ignoreCase = true)
    }
    if (byName != null) {
        return byName.displayName
    }
    
    val defaultModel = LlmClient.AVAILABLE_MODELS.find { it.choice == defaultChoice }
        ?: LlmClient.AVAILABLE_MODELS[1]
    println("LLM не найдена. Используется модель по умолчанию: ${defaultModel.displayName}")
    return defaultModel.displayName
}

/**
 * Определяет, нужно ли продолжать цикл.
 */
fun shouldContinue(input: String?): Boolean {
    if (input == null) return false
    val trimmed = input.trim().lowercase()
    return when (trimmed) {
        "n", "no", "н", "нет" -> false
        else -> true
    }
}

/**
 * Читает температуру от пользователя с валидацией.
 */
fun readTemperature(prompt: String = "Введите температуру (0.0 - 2.0): ", default: Double = 0.7): Double {
    print(prompt)
    System.out.flush()
    val input = readLineSafe()?.trim()
    
    if (input.isNullOrEmpty()) {
        println("Используется температура по умолчанию: $default")
        return default
    }
    
    val temperature = input.toDoubleOrNull()
    
    return when {
        temperature == null -> {
            println("Некорректное значение. Используется температура по умолчанию: $default")
            default
        }
        temperature !in 0.0..2.0 -> {
            println("Температура должна быть в диапазоне 0.0 - 2.0. Используется значение по умолчанию: $default")
            default
        }
        else -> temperature
    }
}

// =====================================================================
// Загрузка файла как запроса (Week 2 Day 5)
// =====================================================================

/**
 * Регулярное выражение для поиска путей к файлам *.txt / *.md внутри текста.
 *
 * Цепляет ТОЛЬКО пути, содержащие разделитель директорий (\ или /),
 * чтобы не путать упоминания имён файлов (например, «skill-all-ru.md»)
 * с реальными путями к существующим файлам.
 *
 * Поддерживает:
 * - Абсолютные Windows-пути: C:\dir\file.md, D:\path\to\file.txt
 * - Абсолютные Unix-пути:   /home/user/file.md
 * - Относительные пути:     ./docs/file.txt, ../readme.md, docs/sub/file.txt
 * - UNC-пути:              \\server\share\file.txt
 *
 * НЕ цепляет bare filenames без \ или / (например, «notes.txt»).
 *
 * Путь заканчивается на .txt или .md (регистронезависимо).
 */
private val FILE_PATH_REGEX = Regex(
    """(?:[A-Za-z]:[\\/]|\\\\[^\\]+\\)(?:[^"'\s]+\\)*[^"'\s]+\.(?:txt|md)|""" +  // Windows absolute / UNC
    """/(?:[^"'\s]+/)*[^"'\s]+\.(?:txt|md)|""" +                                    // Unix absolute / relative with ./
    """\.{1,2}/(?:[^"'\s]+/)*[^"'\s]+\.(?:txt|md)|""" +                              // ./file or ../dir/file
    """[^"'\s]+[/\\](?:[^"'\s]+[/\\])*[^"'\s]+\.(?:txt|md)""",                       // Relative with dir separator
    RegexOption.IGNORE_CASE
)

/**
 * Читает запрос пользователя с поддержкой загрузки из файла (*.txt, *.md).
 *
 * **Три режима работы:**
 *
 * 1. **Явное указание файла** — ввод начинается с `@` или `file:`:
 *    - `@D:\docs\readme.md` → загружает содержимое файла как запрос
 *    - `file:./notes.txt`  → загружает содержимое файла как запрос
 *
 * 2. **Весь ввод — путь к файлу** — текст целиком является путём к *.txt / *.md:
 *    - `D:\docs\readme.md` → загружает содержимое как запрос
 *
 * 3. **Путь внутри текста запроса** — путь найден в любой части строки:
 *    - `переведи половину на русский D:\file.md`
 *      → загружает `D:\file.md` и добавляет его содержимое К тексту запроса:
 *      `переведи половину на русский\n\n[СОДЕРЖИМОЕ ФАЙЛА D:\file.md]:\n...`
 *
 * Найдено несколько файлов — загружаются все.
 * Файл не найден — путь остаётся в тексте как есть (LLM увидит путь, но не прочитает).
 *
 * @param prompt  текст приглашения
 * @param default значение по умолчанию
 * @return текст запроса (из файла или введённый вручную)
 */
fun readQueryOrFile(prompt: String, default: String): String {
    print(prompt)
    System.out.flush()
    println("(введите запрос, путь к *.txt/*.md файлу, или @путь/к/файлу)")
    val input = readLineSafe()?.trim()

    if (input.isNullOrEmpty()) {
        println("Запрос не может быть пустым. Используется запрос по умолчанию.")
        return default
    }

    // ── Режим 1: явное указание файла через @ или file: ──
    val explicitPath = when {
        input.startsWith("@") -> input.substring(1).trim()
        input.startsWith("file:", ignoreCase = true) -> input.substring(5).trim()
        else -> null
    }

    if (explicitPath != null) {
        return loadSingleFile(explicitPath, default) ?: default
    }

    // ── Режим 2: весь ввод — путь к файлу ──
    val wholePath = input.trim()
    val wholeFile = File(wholePath)
    val wholeIsFile = wholeFile.exists() && wholeFile.isFile
    val wholeIsValid = wholePath.endsWith(".txt", ignoreCase = true) ||
            wholePath.endsWith(".md", ignoreCase = true)

    if (wholeIsFile && wholeIsValid) {
        val content = readFileContent(wholeFile, wholePath)
        if (content != null) {
            println()
            println("Загружен файл: $wholePath (${wholeFile.length()} байт)")
            println()
            println("Содержимое файла (отформатировано по ширине ${ConfigKeys.DEFAULT_COLUMN_WIDTH}):")
            printlnColumn(content)
            println()
            return content
        }
    }

    // ── Режим 3: поиск путей внутри текста запроса ──
    val matches = FILE_PATH_REGEX.findAll(input)
    val foundPaths = matches.map { it.value }.toList()

    if (foundPaths.isEmpty()) {
        // Обычный текст запроса (без файлов)
        println()
        println("Ваш запрос (отформатирован по ширине ${ConfigKeys.DEFAULT_COLUMN_WIDTH}):")
        printlnColumn(input)
        println()
        return input
    }

    // Нашли один или несколько путей к файлам в тексте
    val loadedContents = mutableMapOf<String, String>()
    val notFoundPaths = mutableListOf<String>()
    var modifiedInput = input

    for (path in foundPaths) {
        val file = File(path)
        if (file.exists() && file.isFile) {
            val content = readFileContent(file, path)
            if (content != null) {
                loadedContents[path] = content
            } else {
                notFoundPaths.add(path)
            }
        } else {
            notFoundPaths.add(path)
        }
    }

    // Если ни один файл не загружен — возвращаем исходный текст
    if (loadedContents.isEmpty()) {
        if (notFoundPaths.isNotEmpty()) {
            println()
            println("⚠ Указанные файлы не найдены:")
            for (p in notFoundPaths) {
                println("  • $p")
            }
            println("  Пути останутся в тексте запроса, но LLM не сможет их прочитать.")
        }
        println()
        println("Ваш запрос (отформатирован по ширине ${ConfigKeys.DEFAULT_COLUMN_WIDTH}):")
        printlnColumn(input)
        println()
        return input
    }

    // Успешно загружены файлы
    println()
    for ((path, content) in loadedContents) {
        val file = File(path)
        println("✔ Загружен файл: $path (${file.length()} байт)")
    }
    if (notFoundPaths.isNotEmpty()) {
        println("⚠ Не найдены:")
        for (p in notFoundPaths) {
            println("  • $p")
        }
    }

    // Формируем итоговый запрос: исходный текст + содержимое файлов
    val sb = StringBuilder(input)
    for ((path, content) in loadedContents) {
        sb.append("\n\n[СОДЕРЖИМОЕ ФАЙЛА ")
        sb.append(path)
        sb.append("]:\n")
        sb.append(content)
    }
    val result = sb.toString()

    println()
    println("Итоговый запрос (отформатирован по ширине ${ConfigKeys.DEFAULT_COLUMN_WIDTH}):")
    printlnColumn(result)
    println()
    return result
}

/**
 * Читает содержимое файла. Возвращает null при ошибке.
 */
private fun readFileContent(file: File, displayPath: String): String? {
    return try {
        val content = file.readText().trim()
        if (content.isEmpty()) {
            println("⚠ Файл '$displayPath' пуст.")
            null
        } else {
            content
        }
    } catch (e: Exception) {
        println("⚠ Ошибка чтения файла '$displayPath': ${e.message}")
        null
    }
}

/**
 * Загружает ОДИН файл как полный запрос (режимы 1 и 2).
 * Возвращает содержимое или null при ошибке.
 */
private fun loadSingleFile(filePath: String, default: String): String? {
    val file = File(filePath)
    if (!file.exists() || !file.isFile) {
        println("⚠ Файл '$filePath' не найден. Используется запрос по умолчанию.")
        return default
    }
    val content = readFileContent(file, filePath)
    if (content == null) {
        println("Используется запрос по умолчанию.")
        return default
    }
    println()
    println("Загружен файл: $filePath (${file.length()} байт)")
    println()
    println("Содержимое файла (отформатировано по ширине ${ConfigKeys.DEFAULT_COLUMN_WIDTH}):")
    printlnColumn(content)
    println()
    return content
}
