# Every Run Keeps Its Code — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every run's code survives: one line per run in `problems/<id>/runs.jsonl`, written when the run's code is attached, so part 4.3 can pair failed gradings with the attempt that corrected them.

**Architecture:** Part 4.2 of `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md`. A domain method names a grading (`recordId`), a new store adapter `RunLog` appends idempotently, and `FileDerivedArtifacts.writeCode` calls it for runs. Nothing on the capture path changes; the line rides the existing commits.

**Tech Stack:** Kotlin (JVM 25), kotlinx.serialization, JUnit 5 + Kotest, Gradle.

---

## Measured before planning — and what it changed

**The race the spec feared is real only below ~0.3 s.** Measured 2026-10-07 10:29 KST on lesson 59035: the owner alternated two different queries as fast as possible, four runs. A poller on `Solution.sql` saw the attached code land 0.53 / 0.28 / 0.25 / 0.24 s after each run's record, and every run got its own query (WRONG, PASS, WRONG, PASS alternating correctly). The shortest human gap between runs was 1.6 s. Misattribution needs a second press within the fetch window of ~0.3 s.

**And it cannot be detected where the spec put it.** `ChannelCapture` handles frames in order and awaits the attachment inside `settle`, so a `start` pressed during the fetch waits in the socket queue until the attachment returns — there is nothing to compare against at attach time. So:

- **No `codeUncertain` flag.** The spec's 4.2 risk becomes an accepted cost, recorded in the ADR. A misattribution leaves a signature part 4.3 can flag after the fact: two consecutive runs with identical code and different verdicts.
- **No `sameAsPrevious`.** A pending run attached by the startup retry arrives out of order, and "previous" would be wrong. Every line carries full code (~200 B for SQL, a few KB for an algorithm); readers dedupe consecutive identical code.

## Changed in review

- **The line was slimmed to four keys** (`recordId`, `language`, `codeFetchedAt`, `code`). Verdicts and messages stay in `log/submissions.jsonl`, the only authority; a copy would disagree once a classification rule changes, as #350 just did. Part 4.3 joins by `recordId`.
- **`codeFetchedAt` was added.** A run attached late (startup retry after an expired session or rate limit) gets the page's current code, possibly a later run's. Comparing it with the next record's `ts` (when that grading finished being recorded) catches this. Limit: it can miss a second Run pressed within the ~0.3 s fetch window that finishes after the fetch.
- **Idempotency checks complete lines only**, so a crash-torn line cannot block the retry.
- **Heal reads the last byte** of the file instead of the whole content.
- **The `Clock` is injected through `FileDerivedArtifacts`** into `RunLog`, the same one that stamps record `ts`.

## Ground rules

- Read `CLAUDE.md` and `docs/development-rules.md`: TDD pairs, three-layer tests, English artifacts, ktlint 120, issue-first branch, squash PR. Gates: `./scripts/check.sh`, `./scripts/test.sh`, `./scripts/build.sh`; the push hook runs `scripts/guards.sh` (no Korean in comments; Korean twins must carry the right `translated-from` hash).
- Branch: `feat/351-every-run-keeps-its-code` from a fresh `main`. Create the issue first.

## File map

| File | Change |
|---|---|
| `src/main/kotlin/com/brokenfinger/tracker/domain/SubmissionRecord.kt` | `fun recordId(): String` |
| `src/test/kotlin/com/brokenfinger/tracker/domain/SubmissionRecordTest.kt` | unit tests |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/RecordLayout.kt` | `fun runLog(lessonId, title): Path` |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/RunLog.kt` | new — idempotent append |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/store/RunLogTest.kt` | new — layer tests |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/FileDerivedArtifacts.kt` | call `RunLog` for runs |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/store/FileDerivedArtifactsTest.kt` | two tests |
| `src/main/resources/vault/README.md`, `README.ko.md` | one layout line each; twin hash |
| `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md` | 4.2 updated to the measurement |
| `docs/llm-wiki/wiki/decisions/2026-10-07-every-run-keeps-its-code.md` | new ADR |
| `docs/llm-wiki/wiki/decisions/2026-10-07-database-failures-that-said-something.md` | Outcome: live times |
| `docs/llm-wiki/index.md`, `.harness/state/progress.md` | entries |

---

### Task 1: A grading's name

**Files:** `domain/SubmissionRecord.kt`, test `domain/SubmissionRecordTest.kt`

- [x] **Step 1: Failing tests** (append inside the test class; add imports as needed)

```kotlin
    /** #343: a grading is its write time and its bytes; this is the one string that says so. */
    @Test
    fun `a record names itself by its time and its capture key`() {
        val record = aSubmissionRecord(
            ts = OffsetDateTime.parse("2026-10-03T15:23:52.318205458+09:00"),
            captureKey = CaptureKey("39e412c5dde20f36"),
        )

        record.recordId() shouldBe "2026-10-03T15:23:52.318205458+09:00#39e412c5dde20f36"
    }

    @Test
    fun `a correction carries the name of the record it corrects`() {
        val pending = aSubmissionRecord(codePending = true, codePath = null)

        pending.copy(codePending = false, codePath = "x").recordId() shouldBe pending.recordId()
    }
```

- [x] **Step 2:** `./gradlew test --tests 'com.brokenfinger.tracker.domain.SubmissionRecordTest'` → FAIL (unresolved `recordId`).
- [x] **Step 3: Implement** in `SubmissionRecord`, after `isSubmission()`:

```kotlin
    /**
     * This grading's name — its write time and its capture key, in the text form the log
     * stores the time in. The pair is what [com.brokenfinger.tracker.application.RecordHistory]
     * resolves on (#343): the key alone is the grading's bytes and repeats; the time does not.
     */
    fun recordId(): String = "${ts.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}#${captureKey.value}"
```

Import `java.time.format.DateTimeFormatter`. `OffsetDateTimeSerializer` uses the same formatter, so the id's time is exactly the text in the log line.

- [x] **Step 4:** run → PASS.

### Task 2: The run log

**Files:** create `adapter/store/RunLog.kt`, test `adapter/store/RunLogTest.kt`; modify `adapter/store/RecordLayout.kt`

- [x] **Step 1: Layout** — in `RecordLayout`, beside `statementFile`:

```kotlin
    /** One line per run, with its code (#mistake-patterns 4.2) — `problems/<id>/runs.jsonl`. */
    fun runLog(lessonId: Long, title: String?): Path = problemDirectory(lessonId, title).resolve(RUN_LOG)
```

and in its companion `private const val RUN_LOG = "runs.jsonl"`. Remove the `#mistake-patterns 4.2` fragment if ktlint or the guard dislikes it; cite the spec path instead.

- [x] **Step 2: Failing tests** — `RunLogTest`:

```kotlin
package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import com.brokenfinger.tracker.support.fixtures.aTestcaseResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * Layer test over a real directory (dev rules §6.1). A run's code is the evidence part 4.3 pairs
 * into repair steps; what is pinned here is that each run leaves exactly one readable line.
 */
class RunLogTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `a run leaves one line with its name, verdict and full code`() {
        val run = aRun(verdict = Verdict.WRONG)

        log().append(run, "select 1\n")

        val line = lines().single()
        line["recordId"] shouldBe run.recordId()
        line["verdict"] shouldBe "WRONG"
        line["code"] shouldBe "select 1\n"
    }

    @Test
    fun `the failing case's message is kept, which is where a database error lives`() {
        val tuple = "(1054, \"Unknown column 'USER_ID' in 'field list'\")"
        val run = aRun(
            verdict = Verdict.COMPILE_ERROR,
            testcases = listOf(aTestcaseResult(passed = false, msg = tuple, runTime = null, returnedResult = false)),
        )

        log().append(run, "select USER_ID from x")

        lines().single()["failedMessage"] shouldBe tuple
    }

    /** The startup retry re-attaches a record whose correction never landed; it must not double. */
    @Test
    fun `attaching the same run twice leaves one line`() {
        val run = aRun()

        log().append(run, "a")
        log().append(run, "a")

        lines() shouldHaveSize 1
    }

    @Test
    fun `two runs with identical bytes at different times are two lines`() {
        val first = aRun(ts = "2026-10-03T15:21:02+09:00")
        val second = first.copy(ts = OffsetDateTime.parse("2026-10-03T15:23:52+09:00"))

        log().append(first, "same")
        log().append(second, "same")

        lines() shouldHaveSize 2
    }

    @Test
    fun `a submit writes no run line — it owns an attempt file instead`() {
        log().append(aSubmissionRecord(action = GradingAction.SUBMIT), "x")

        Files.exists(layout().runLog(120804, "두 수의 곱 구하기")) shouldBe false
    }

    @Test
    fun `a torn last line is healed rather than glued to the next`() {
        val file = layout().runLog(120804, "두 수의 곱 구하기")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{\"recordId\":\"torn")

        log().append(aRun(), "a")

        Files.readAllLines(file).last().startsWith("{\"recordId\":\"2026") shouldBe true
    }

    private fun aRun(
        ts: String = "2026-10-03T15:21:02+09:00",
        verdict: Verdict = Verdict.WRONG,
        testcases: List<com.brokenfinger.tracker.domain.TestcaseResult> =
            listOf(aTestcaseResult(passed = false, msg = null, runTime = null, returnedResult = true)),
    ) = aSubmissionRecord(
        ts = OffsetDateTime.parse(ts),
        action = GradingAction.RUN,
        attempt = 0,
        verdict = verdict,
        testcases = testcases,
    )

    private fun layout() = RecordLayout(root)

    private fun log() = RunLog(layout())

    private fun lines(): List<Map<String, String?>> =
        Files.readAllLines(layout().runLog(120804, "두 수의 곱 구하기")).filter { it.isNotBlank() }.map { line ->
            Json.parseToJsonElement(line).jsonObject.mapValues { (_, v) -> runCatching { v.jsonPrimitive.content }.getOrNull() }
        }
}
```

Adjust: the fixture title is `두 수의 곱 구하기`; read `aSubmissionRecord` defaults and use its `title`. If `aTestcaseResult` lacks a parameter used here, read `TestcaseResultFixtures.kt` and match. Wrap lines over 120. Korean in a test *string literal* is fine; it may not appear in comments.

- [x] **Step 3:** run `RunLogTest` → FAIL (no `RunLog`).
- [x] **Step 4: Implement** `adapter/store/RunLog.kt`:

```kotlin
package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.SubmissionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * `problems/<id>/runs.jsonl` — one line per run, with the code it ran (spec 2026-10-07 §4.2).
 *
 * A run owns no attempt file and `Solution.<ext>` is overwritten by the next one, so before
 * this the code of every run but the last was lost — and the correction between a failed run
 * and the next is the evidence a recurring mistake leaves. Full code on every line: a few KB at
 * most, and a pending run attached late by the startup retry arrives out of order, so a line
 * that said "same as the previous" would name the wrong neighbour.
 *
 * Append-only and idempotent by [SubmissionRecord.recordId]: the retry may attach a run twice.
 * Not read on the capture path beyond that check.
 */
class RunLog(private val layout: RecordLayout) {
    fun append(record: SubmissionRecord, code: String) {
        if (record.action != GradingAction.RUN) return
        val file = layout.runLog(record.lessonId, record.title)
        if (alreadyHolds(file, record.recordId())) return
        Files.createDirectories(file.parent)
        Files.writeString(file, heal(file) + format.encodeToString(lineOf(record, code)) + "\n", CHARSET, *APPEND)
    }

    private fun lineOf(record: SubmissionRecord, code: String) = RunLine(
        recordId = record.recordId(),
        ts = record.recordId().substringBefore('#'),
        language = record.language,
        outcome = record.outcome.name,
        verdict = record.verdict?.name,
        failedMessage = record.testcases.sortedBy { it.id }.firstOrNull { it.hasFailed() }?.msg,
        errorText = record.errorText,
        code = code,
    )

    private fun alreadyHolds(file: Path, recordId: String): Boolean {
        if (!Files.isRegularFile(file)) return false
        val needle = "\"recordId\":${format.encodeToString(recordId)}"
        return String(Files.readAllBytes(file), CHARSET).lineSequence().any { it.contains(needle) }
    }

    private fun heal(file: Path): String {
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) return ""
        return if (Files.readAllBytes(file).last() == '\n'.code.toByte()) "" else "\n"
    }

    @Serializable
    private data class RunLine(
        val recordId: String,
        val ts: String,
        val language: String,
        val outcome: String,
        val verdict: String?,
        val failedMessage: String?,
        val errorText: String?,
        val code: String,
    )

    private companion object {
        val CHARSET = StandardCharsets.UTF_8
        val APPEND = arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        val format = Json { explicitNulls = false }
    }
}
```

`explicitNulls = false` leaves absent what was never recorded (absent is not zero). If `encodeToString` needs the reified import or `Json` requires `@OptIn`, follow the compiler. Keep functions under 10 lines where the team standard asks.

- [x] **Step 5:** run `RunLogTest` → PASS. Then break `alreadyHolds` (return `false`) and confirm `attaching the same run twice leaves one line` fails; restore.

### Task 3: Runs write their line when their code is attached

**Files:** `adapter/store/FileDerivedArtifacts.kt`, test `FileDerivedArtifactsTest.kt`

- [x] **Step 1: Failing tests** after `a run points at the solution file, the only one it owns`:

```kotlin
    @Test
    fun `a run also leaves its code in the run log`() {
        val record = aSubmissionRecord(action = GradingAction.RUN, attempt = 0)

        artifacts().writeCode(record, CODE_V1)

        val log = root.resolve("problems/120804-두-수의-곱-구하기/runs.jsonl")
        Files.readAllLines(log).single() shouldContain record.recordId()
    }

    @Test
    fun `a submit leaves no run log`() {
        artifacts().writeCode(aSubmissionRecord(action = GradingAction.SUBMIT, attempt = 2), CODE_V1)

        Files.exists(root.resolve("problems/120804-두-수의-곱-구하기/runs.jsonl")) shouldBe false
    }
```

(`shouldContain` from `io.kotest.matchers.string.shouldContain`.)

- [x] **Step 2:** run → first FAILS.
- [x] **Step 3: Implement** — in `FileDerivedArtifacts`: `private val runs = RunLog(layout)` beside `artifacts`, and in `writeCode` after `writeAttempt`:

```kotlin
        runs.append(record, code)
```

Add one sentence to `writeCode`'s KDoc: a run also appends its code to `runs.jsonl`, the only place a run's code outlives the next run.

- [x] **Step 4:** run `FileDerivedArtifactsTest` and `CodeAttachmentTest` → PASS. Full gates.
- [x] **Step 5: Commit** code and tests: `feat(store): keep every run's code in the problem's run log` with body citing the 10:29 measurement and `Refs #351`.

### Task 4: Docs

- [x] **Step 1: Vault README.** In `src/main/resources/vault/README.md`'s layout block, after the `Solution.<ext>` line:

```
├── runs.jsonl         Every code run: its time, verdict, error and the code it ran
```

In `README.ko.md`, the same place:

```
├── runs.jsonl         코드 실행 기록 — 시각, 판정, 오류, 실행한 코드
```

and update its first line's hash: `git hash-object src/main/resources/vault/README.md` → `<!-- translated-from: README.md@<that hash> -->`. Run `scripts/guards.sh`; `RecordRepositoryTemplateTest` must still pass (read it first if it pins the layout block).

- [x] **Step 2: Spec.** In `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md` §4.2: remove `sameAsPrevious` from the example and the bullets; replace the "Open risk — measure first" paragraph with the measurement in this plan's opening section (0.24–0.53 s fetch, 1.6 s fastest human gap, four runs all attributed correctly, the in-order capture making detection impossible at attach time, and the after-the-fact signature for 4.3). Mark §6's 4.2 row accordingly.

- [x] **Step 3: ADR** `docs/llm-wiki/wiki/decisions/2026-10-07-every-run-keeps-its-code.md`:

```markdown
---
type: decision
project: programmers-tracker
tags: [storage, code, measurement, mcp]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# Every run keeps its code

## Context

[[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]] needs the correction between a
failed grading and the next attempt, and a run kept no code: `Solution.<ext>` is overwritten by
the next one. Lesson 273711's eleven runs left none.

## Options considered

1. **A file per run** (`runs/NNN.<ext>`) — readable in the vault, dozens per problem.
2. **One `runs.jsonl` per problem**, a line per run with full code — chosen.
3. **Diffs instead of code** — smaller, but a pending run attached late by the startup retry
   arrives out of order and a diff would be against the wrong neighbour.

## Decision

Option 2, written by the store when a run's code is attached, idempotent by `recordId`, committed
with the records as everything else in the problem directory is.

## Rationale

Code is small (an SQL run ~200 B). One file keeps the vault readable and gives part 4.3 a single
place to read. Measured 2026-10-07 10:29 on lesson 59035: four runs alternated as fast as a human
can, the code landed 0.24–0.53 s after each record, and every run got its own query.

## Accepted costs

- **A run's code can be the next run's** if Run is pressed again within the ~0.3 s fetch window.
  The capture handles frames in order and awaits the attachment, so the next `start` is not
  visible at attach time and this cannot be detected there. The fastest measured human gap was
  1.6 s. Part 4.3 can flag the signature afterwards: consecutive runs, identical code, different
  verdicts.
- Every run's code is published with the records (the owner's decision, 2026-10-07).
- Runs recorded before this have no line.

## Outcome

Live acceptance: one problem solved with several runs leaves one line per run.
```

- [x] **Step 4:** In `2026-10-07-database-failures-that-said-something.md` Outcome, replace "Live acceptance pending: …" with: `Verified live 2026-10-07 on lesson 59035 after rebuilding from f89960c (PR #350): a run of a query naming a missing column at 10:06:17 was recorded COMPILE_ERROR with the 1054 tuple, and a submit with the wrong order at 10:06:55 was recorded WRONG.`
- [x] **Step 5:** index entry for the new ADR; progress entry; tick this plan's boxes.
- [ ] **Step 6:** `scripts/guards.sh` passes; commit `docs: every run keeps its code — measurement, ADR, vault layout`, `Closes #351`.

### Task 5: PR, merge, live

- [ ] Push, PR, watch CI to completion, squash-merge, rebuild the container.
- [ ] **Live:** the owner runs any problem two or three times with different code; `problems/<id>/runs.jsonl` gains one line per run with the right code. Record the time in the ADR's Outcome on the next branch.

## Self-review against the spec (part 4.2)

| Spec item | Here |
|---|---|
| `runs.jsonl`, one line per run, written at attach | Tasks 2–3 |
| Full code; diffs on read | Task 2 (and `sameAsPrevious` dropped, with the reason) |
| `Solution.<ext>` and `attempts/` unchanged | Task 3 only adds a call |
| Committed, no extra commits | Unchanged commit paths; the file sits in the problem directory |
| Returned tables not copied | Not written |
| Past history has no run code | Accepted cost |
| Fetch race: measure, then mark | Measured; marking replaced by an after-the-fact signature (spec updated in Task 4) |
