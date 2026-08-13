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
    implementation(libs.jsoup)
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

// Branch coverage, floor per package, deferred in `2026-08-05-ci-guard-scoping` and widened
// past the calculators in #272.
//
// **It reads every package the report contains rather than looking up names it remembers.**
// The version before this asked for `domain/calc` by name and got exactly that: Kover emits
// `domain/calc/runner` as a separate package, so the gate measured 132 branches and ignored the
// 862 in the same directory — the largest uncovered pool in the repository, inside the one place
// we told ourselves was held to 95%. Inverting the lookup makes that failure structural rather
// than something a reader has to notice ([[decisions/2026-08-10-guards-must-prove-they-ran]]).
//
// Exemptions are named here with their reason, and an exemption that no longer matches a package
// fails the build — otherwise a rename turns it into a silent free pass, which is the same defect
// one level up.
val verifyBranchCoverage =
    tasks.register("verifyBranchCoverage") {
        description = "Fails when any package's hand-written branch coverage falls below its floor."
        dependsOn(tasks.named("koverXmlReport"))
        val report = layout.buildDirectory.file("reports/kover/report.xml")
        doLast {
            val xml = report.get().asFile
            check(xml.isFile) { "no Kover XML report at $xml" }
            val measured = handWrittenBranchesByPackage(xml)
            // A parse that quietly returned nothing would pass every tree, which is the shape
            // #194 was about. There is always at least one package.
            check(measured.isNotEmpty()) { "read no packages from $xml — did the report format change?" }

            val renamed = (coverageExempt.keys + coverageFloors.keys) - measured.keys
            check(renamed.isEmpty()) {
                "named in the coverage settings but absent from the report: ${renamed.joinToString()}. " +
                    "Renamed or deleted? A rule that matches nothing rules nothing and hides that it does."
            }

            // Every package is reported; only the ones with a floor are enforced. An exemption
            // that also hid its number would be the one package nobody could check on, which is
            // how a stated "raising it is tracked separately" quietly becomes permanent (#282).
            val failures = measured.toSortedMap().mapNotNull { (pkg, tally) ->
                val (covered, total) = tally
                val percent = if (total == 0) 100 else covered * 100 / total
                val floor = coverageFloors[pkg] ?: packageFloor
                val note = if (pkg in coverageExempt) "  — exempt" else "  (floor $floor%)"
                logger.lifecycle(
                    "  %-40s %3d%%  (%d/%d branches)%s"
                        .format(pkg.removePrefix(packageRoot), percent, covered, total, note),
                )
                if (pkg in coverageExempt || percent >= floor) return@mapNotNull null
                "$pkg is $percent% (${total - covered} of $total uncovered), below $floor%"
            }
            check(failures.isEmpty()) {
                "branch coverage below floor:\n  " + failures.joinToString("\n  ")
            }
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

val packageRoot = "com/brokenfinger/tracker/"

/** What every package holds unless it says otherwise. Measured at 85% across the tree when set (#272). */
val packageFloor = 80

/**
 * Floors that differ from [packageFloor], each with the reason it differs. A number without an
 * argument is a number nobody can revisit, so both directions are stated here rather than being
 * special cases in the task.
 */
val coverageFloors = mapOf(
    // Higher: these decide verdicts, they have no I/O to make coverage hard, and an unexercised
    // branch is the silent-wrong-data failure the constitution ranks worst.
    "com/brokenfinger/tracker/domain/calc" to 95,
    // Lower: the composition root. What is left below the general floor is bean factories'
    // `?:` defaults and one private suspend helper — branches whose only honest test would
    // assert Spring's wiring, which the context-load test and the running server already do.
    // The classes in here that are *not* configuration (BackupSchedule, BuildIdentity) are held
    // to nothing lower: they are unit-tested, and moving them out would let this rise.
    "com/brokenfinger/tracker/adapter/config" to 65,
)

/**
 * Packages held to no floor, each with the reason. Verified to exist on every run — an
 * exemption naming a package that is gone excludes nothing while still reading as a decision.
 */
val coverageExempt = mapOf(
    "com/brokenfinger/tracker/domain/calc/runner" to
        "runner scaffolding for seven languages: it generates files and decides no verdict, so the " +
        "argument that puts domain/calc at 95% does not transfer to it. Raising it is tracked separately.",
)

/**
 * Branch counters that correspond to something a person wrote — not to what the Kotlin compiler
 * emitted underneath.
 *
 * Default parameter values compile to one bitmask test per parameter (`if ((mask and n) != 0)
 * param = default`), so a data class with 15 defaulted fields carries 15 forks that every call
 * site only ever takes one way. `SubmissionRecord` alone contributed 69 such branches and made
 * `domain` read 57% while its line coverage was 100%. Counting those measures how many optional
 * fields our data classes have, and no test should ever be written to move the number.
 */
val generatedMembers = setOf("<init>", "equals", "hashCode", "copy", "copy\$default", "toString")

/**
 * Every package in a Kover XML report, mapped to its hand-written (covered, total) branches.
 *
 * Summed from method counters rather than read off the package counter, which is what excludes
 * [generatedMembers]. Parsed with the JDK's own DOM rather than a regex: the previous regex
 * silently matched one package and skipped its sub-package for weeks (#272), and nested XML is
 * not something to match by hand twice.
 */
fun handWrittenBranchesByPackage(report: File): Map<String, Pair<Int, Int>> {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        // The report declares the JaCoCo DTD by relative path; resolving it would hit the disk
        // or the network for a grammar this does not validate against.
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    val document = report.inputStream().use { factory.newDocumentBuilder().parse(it) }
    val packages = document.getElementsByTagName("package")
    return (0 until packages.length).associate { index ->
        val pkg = packages.item(index) as org.w3c.dom.Element
        pkg.getAttribute("name") to branchesIn(pkg)
    }
}

private fun branchesIn(pkg: org.w3c.dom.Element): Pair<Int, Int> {
    var covered = 0
    var total = 0
    val methods = pkg.getElementsByTagName("method")
    for (index in 0 until methods.length) {
        val method = methods.item(index) as org.w3c.dom.Element
        if (method.getAttribute("name") in generatedMembers) continue
        val counters = method.getElementsByTagName("counter")
        for (position in 0 until counters.length) {
            val counter = counters.item(position) as org.w3c.dom.Element
            if (counter.getAttribute("type") != "BRANCH") continue
            val hit = counter.getAttribute("covered").toInt()
            covered += hit
            total += hit + counter.getAttribute("missed").toInt()
        }
    }
    return covered to total
}
