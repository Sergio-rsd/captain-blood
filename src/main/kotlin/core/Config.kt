package core

import java.util.Properties

/**
 * Загрузка конфигурации из файла config.properties в resources/.
 *
 * @return объект Properties с конфигурацией
 */
fun loadConfig(): Properties {
    val props = Properties()
    val classLoader = Thread.currentThread().contextClassLoader
    val stream = classLoader.getResourceAsStream(ConfigKeys.CONFIG_FILE)
        ?: throw IllegalStateException("Файл ${ConfigKeys.CONFIG_FILE} не найден в resources/")
    stream.use { props.load(it) }
    return props
}

/**
 * Загружает конфигурацию с обработкой ошибок, выводя сообщение при неудаче.
 *
 * @return объект Properties или null если конфигурация не найдена
 */
fun loadConfigOrNullWithMessage(): Properties? {
    return try {
        loadConfig()
    } catch (e: Exception) {
        println("Внимание: Не удалось загрузить конфигурацию. Используются моки.")
        println("Создайте файл ${ConfigKeys.CONFIG_FILE} в resources/ с ключами API.")
        println()
        null
    }
}
