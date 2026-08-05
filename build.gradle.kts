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
    // Spring reads Kotlin method parameters through kotlin-reflect. It arrives transitively
    // on the TEST classpath via spring-boot-starter-test, so slice tests pass without it
    // while the running application throws ClassNotFoundException from every
    // @ExceptionHandler — measured 2026-08-05 by starting the server (#23).
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.spring.boot.starter.test)
    // The one Spring slice the test-environment ADR allows: web controllers.
    testImplementation(libs.spring.boot.webmvc.test)
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

// A JUnit test method whose expression body returns something other than Unit is silently
// NOT RUN — no error, no skip notice, nothing. A test that never runs is indistinguishable
// from a test that passes, which is how eight CodeFetch tests reached main looking green in
// #21 without ever executing. This makes the invisible case visible: every test class in the
// source tree must produce a result file.
val verifyEveryTestClassRan =
    tasks.register("verifyEveryTestClassRan") {
        description = "Fails when a test class produced no result file — usually a non-Unit test method."
        // Run explicitly after a FULL suite (scripts/test.sh, CI), never as finalizedBy: a
        // filtered run legitimately leaves most classes without results.
        val sources = fileTree("src/test/kotlin") { include("**/*.kt") }
        val resultsDir = layout.buildDirectory.dir("test-results/test")
        inputs.files(sources)
        doLast {
            val declared = sources.files
                .filter { it.readText().contains("@Test") }
                .map { it.nameWithoutExtension }
                .toSortedSet()
            val ran = (resultsDir.get().asFile.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".xml") }
                .map { it.nameWithoutExtension.substringAfterLast('.') }
                .toSortedSet()
            val missing = declared - ran
            check(missing.isEmpty()) {
                "these test classes declare @Test but produced no results, so they never ran: " +
                    missing.joinToString()
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

// Measures whether `run` saves code — see LiveCodeFetch. Fetch, edit in the browser, run,
// fetch again: if the hash follows the edit, `run` saves.
tasks.register<JavaExec>("liveCodeFetch") {
    description = "Fetches the saved code of a problem from the real Programmers page."
    group = "verification"
    mainClass.set("com.brokenfinger.tracker.protocol.LiveCodeFetchKt")
    classpath = sourceSets.main.get().runtimeClasspath
    args = (project.findProperty("fetchArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
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
