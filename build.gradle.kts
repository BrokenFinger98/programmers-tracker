plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

group = "com.brokenfinger"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

// LiveObserveKt adds a second main function; the boot jar keeps the Spring entry point.
springBoot {
    mainClass.set("com.brokenfinger.tracker.TrackerApplicationKt")
}

// Spring Boot's BOM manages these transitively, so the version catalog alone does NOT
// control them — the BOM wins unless its own properties are overridden here.
// Measured against the 4.1.0 BOM: it pins coroutines 1.10.2, but Ktor 3.5.2 bytecode
// needs 1.11.0 APIs (without this the WebSocket client dies at connect with
// NoSuchMethodError), and it pins Kotlin 2.3.21 while our compiler plugin is 2.4.10.
// Re-check both with `gradle dependencyInsight` after every Spring Boot upgrade.
extra["kotlin-coroutines.version"] = libs.versions.coroutines.get()
extra["kotlin.version"] = libs.versions.kotlin.get()

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}

// Coverage is report-only for now. A threshold belongs on domain/calc once the pure
// calculators exist — a gate over an empty package is dead config, and a global
// threshold rewards test-gaming rather than covering the failure branches that matter.
kover {
    reports {
        filters {
            excludes {
                classes("com.brokenfinger.tracker.TrackerApplicationKt")
                classes("com.brokenfinger.tracker.protocol.LiveObserveKt")
            }
        }
    }
}

// Default test task = unit + layer only; integration runs live against Programmers
// and is opt-in via the separate integrationTest task (ADR 2026-08-04-test-environment).
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Live-verification entry for issue #6 — subscribes to a real Programmers channel and
// logs every received frame. Usage: ./gradlew liveObserve -Pobserve="algorithm 120804 14643 java"
tasks.register<JavaExec>("liveObserve") {
    description = "Connects to the real Programmers cable and logs every received frame."
    group = "verification"
    mainClass.set("com.brokenfinger.tracker.protocol.LiveObserveKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = (project.findProperty("observe") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

tasks.register<Test>("integrationTest") {
    description = "Runs @Tag(\"integration\") tests against the real Programmers server."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
}
