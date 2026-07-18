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
}

kotlin {
    jvmToolchain(21)
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
