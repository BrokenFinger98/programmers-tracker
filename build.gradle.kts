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

    // So a running instance can say which build it is. `docker compose up` reuses a tagged
    // image without rebuilding, and the only symptom of running a stale one was an absent
    // log line (#60) — a stale image records with whatever version it was built from and says
    // nothing about it.
    //
    // The timestamp is the part that always exists. The commit does not: `.dockerignore`
    // excludes `.git/` on purpose, because it carries every credential ever committed and
    // then removed, so a container build cannot read it. It arrives as a build argument when
    // whoever builds supplies one, and reads "unknown" when they do not — which is honest
    // rather than absent.
    buildInfo {
        properties {
            additional.put("commit", providers.environmentVariable("SOURCE_COMMIT").getOrElse("unknown"))
        }
    }
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
    // The Kotlin runner's execution proof compiles generated source for real (#84).
    testImplementation(libs.kotlin.compiler.embeddable)
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
                classes("com.brokenfinger.tracker.protocol.LiveCodeFetchKt")
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
        // Depends on `test` rather than merely following it on the command line: without
        // this the check can run against an empty results directory and report every class
        // as missing. It passed locally only because a previous run had left results behind
        // — the exact stale-state false pass this task exists to catch, committed by the
        // task itself (measured in CI, #23).
        dependsOn(tasks.test)
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

// The threshold deferred in `2026-08-05-ci-guard-scoping`, now that domain/calc exists.
// Scoped to the pure calculators on purpose: they decide verdicts, they have no I/O to make
// coverage hard, and an unexercised branch there is the silent-wrong-data failure the
// constitution ranks worst. A global number would only invite tests that execute code
// without asserting anything.
//
// Read from the XML report rather than a Kover rule because Kover's verification rules
// cannot be narrowed to one package, and a project-wide number would measure the wrong thing.
val verifyCalculatorCoverage =
    tasks.register("verifyCalculatorCoverage") {
        description = "Fails when branch coverage of domain/calc falls below the threshold."
        dependsOn(tasks.named("koverXmlReport"))
        val report = layout.buildDirectory.file("reports/kover/report.xml")
        val minimum = 95
        val watched = "com/brokenfinger/tracker/domain/calc"
        doLast {
            val xml = report.get().asFile
            check(xml.isFile) { "no Kover XML report at $xml" }
            val percent = branchCoverageOf(xml.readText(), watched)
                ?: error("Kover report has no package $watched — was it renamed, or did its tests stop running?")
            check(percent >= minimum) {
                "branch coverage of $watched is $percent%, below the $minimum% these calculators must hold"
            }
            logger.lifecycle("domain/calc branch coverage: $percent%")
        }
    }

// Default test task = unit + layer only; integration runs live against Programmers
// and is opt-in via the separate integrationTest task (ADR 2026-08-04-test-environment).
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }

    // One fork was the default and nothing had chosen it. Measured on a 14-core machine over the
    // whole suite: 30.8s at one fork, 19.9s at two, 17.1s at four — so most of the win is the
    // second fork, and half the cores is the conservative read on a runner that has four (#245).
    //
    // **Forks, not JUnit's parallel mode, and the difference is load-bearing.** A fork is its own
    // JVM running its classes one at a time, so two things that would otherwise collide are fine:
    // `StateLocationTest` sets the `user.home` system property, which is JVM-global, and
    // `McpControllerTest` writes a fixed `build/tmp/` directory rather than a @TempDir — the only
    // test that names it, so no other class can be in it at the same moment. Turning on
    // `junit.jupiter.execution.parallel.enabled` would break both, and neither would fail every
    // time. 41 test classes take a @TempDir and are safe either way.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
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

/**
 * Branch-covered percentage of one package in a Kover XML report, or null when the package
 * is absent. Reads the package's own counter rather than summing classes, so a class added
 * later is included without touching this.
 */
fun branchCoverageOf(xml: String, packagePath: String): Int? {
    val packageBlock = Regex("""<package name="$packagePath">(.*?)</package>""", RegexOption.DOT_MATCHES_ALL)
        .find(xml)?.groupValues?.get(1) ?: return null
    val counter = Regex("""<counter type="BRANCH" missed="(\d+)" covered="(\d+)"/>""")
        .findAll(packageBlock).lastOrNull() ?: return 100
    val missed = counter.groupValues[1].toInt()
    val covered = counter.groupValues[2].toInt()
    return if (missed + covered == 0) 100 else covered * 100 / (missed + covered)
}
