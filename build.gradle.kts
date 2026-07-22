plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "captainblood"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.json:json:20240303")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.slf4j:slf4j-nop:2.0.9")

    // Индексация новой информации в разных форматах (!index-doc): pdf/docx/doc
    implementation("org.apache.pdfbox:pdfbox:2.0.31")
    implementation("org.apache.poi:poi:5.3.0")
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    implementation("org.apache.poi:poi-scratchpad:5.3.0")

    // Unit/integration-тесты (Advanced-курс, День 3 — Уровень 1)
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

application {
    mainClass.set("captainblood.MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "captainblood.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
}

// Локальный запуск watchdog для тестирования: ./gradlew runWatchdog --args="--dry-run"
tasks.register<JavaExec>("runWatchdog") {
    mainClass.set("captainblood.watchdog.WatchdogMainKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
