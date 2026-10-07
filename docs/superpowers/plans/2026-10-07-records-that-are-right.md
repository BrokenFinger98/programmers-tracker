# Records That Are Right — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the record history correct enough to pair gradings into repair steps: stop the readers folding distinct gradings (#343), and resolve the two measured database failures that land as `UNKNOWN` today.

**Architecture:** Part 4.1 of `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md`. One reader rule changes in `application/RecordHistory` (identity becomes `(ts, captureKey)`), and the pure `domain/calc/VerdictResolver` learns two measured message shapes. Every change is pinned by measured fixtures and goes out as its own issue, branch and squash PR.

**Tech Stack:** Kotlin (JVM 25), Spring Boot 4, JUnit 5 + Kotest assertions, kotlinx.serialization, Gradle Kotlin DSL.

---

## Ground rules for whoever executes this

- Read `CLAUDE.md` and `docs/development-rules.md` first. They are binding: TDD pairs, measured fixtures through `FixtureLoader`, nullable protocol fields, English-only artifacts, issue-first branches, squash PRs, no commits to `main`.
- Gates before every PR, all must exit 0: `./scripts/check.sh`, `./scripts/test.sh`, `./scripts/build.sh`. The push hook also runs `scripts/guards.sh` and blocks a push with no `docs/llm-wiki/` change.
- Run one test class: `./gradlew test --tests 'com.brokenfinger.tracker.application.RecordHistoryTest'`.
- The live server is the Docker container `programmers-tracker` on `127.0.0.1:1619`. After a merge: `docker compose build && docker compose up -d --force-recreate`.
- Never copy real identifiers into fixtures without the substitutions listed in `src/test/resources/fixtures/README.md` (dev rules §7.3). Protocol strings (`실패`, MySQL's error text) stay verbatim — they are what is under test.

## File map

| File | Change | Part |
|---|---|---|
| `src/main/kotlin/com/brokenfinger/tracker/application/RecordHistory.kt` | resolve per `(ts, captureKey)` | A |
| `src/test/kotlin/com/brokenfinger/tracker/application/RecordHistoryTest.kt` | two new unit tests | A |
| `src/test/kotlin/com/brokenfinger/tracker/application/RecordQueryTest.kt` | one layer test through the store | A |
| `docs/llm-wiki/wiki/decisions/2026-10-07-a-record-is-its-time-and-its-bytes.md` | new ADR | A |
| `src/main/kotlin/com/brokenfinger/tracker/domain/calc/VerdictResolver.kt` | two measured shapes | B |
| `src/test/kotlin/com/brokenfinger/tracker/domain/calc/VerdictResolverTest.kt` | unit tests | B |
| `src/test/resources/fixtures/sql-submit-wrong.jsonl` | new measured fixture | B |
| `src/test/resources/fixtures/sql-run-error.jsonl` | new measured fixture | B |
| `src/test/resources/fixtures/README.md` | two rows | B |
| `src/test/kotlin/com/brokenfinger/tracker/protocol/parse/GradingMessageMapperTest.kt` | mapper test on the error fixture | B |
| `src/test/kotlin/com/brokenfinger/tracker/application/GradingSessionAssemblerTest.kt` | two assembler tests | B |
| `docs/programmers-protocol.md` | §6, §7, §15 | B |
| `docs/llm-wiki/wiki/decisions/2026-10-07-database-failures-that-said-something.md` | new ADR | B |

---

## Part A — #343: the history keeps every grading

Branch: `fix/343-history-folds-identical-gradings` from a fresh `main`. Issue #343 already exists.

### Task A1: Pin the fold as a failing unit test

**Files:**
- Test: `src/test/kotlin/com/brokenfinger/tracker/application/RecordHistoryTest.kt`

- [x] **Step 1: Add two tests before `private fun stored(...)`**

```kotlin
    /**
     * #343, measured 2026-10-06 on lesson 131537: five wrong SQL runs that returned the same
     * table share one capture key, because the key is derived from the grading's bytes. They
     * are five gradings, and the history must say five.
     */
    @Test
    fun `two gradings with identical bytes at different times are two records`() {
        val key = CaptureKey("cccc000000000001")
        val first = aSubmissionRecord(ts = OffsetDateTime.parse("2026-10-03T15:21:02+09:00"), captureKey = key)
        val second = aSubmissionRecord(ts = OffsetDateTime.parse("2026-10-03T15:23:52+09:00"), captureKey = key)

        val history = RecordHistory.of(stored(first, second))

        history.map { it.ts } shouldContainExactly listOf(first.ts, second.ts)
    }

    /** The rule #343 must not break: a correction carries its original's `ts` and still replaces it. */
    @Test
    fun `a correction still supersedes the grading it repeats when the key is shared`() {
        val key = CaptureKey("cccc000000000002")
        val earlier = aSubmissionRecord(ts = OffsetDateTime.parse("2026-10-03T15:21:02+09:00"), captureKey = key)
        val pending = aSubmissionRecord(
            ts = OffsetDateTime.parse("2026-10-03T15:23:52+09:00"),
            captureKey = key,
            codePending = true,
            codePath = null,
        )
        val attached = pending.copy(codePending = false, codePath = "problems/131537/Solution.sql")

        val history = RecordHistory.of(stored(earlier, pending, attached))

        history shouldHaveSize 2
        history.last().isCodeAttached() shouldBe true
    }
```

Add `import java.time.OffsetDateTime` if the file does not import it yet.

- [x] **Step 2: Run and confirm the first test fails for the right reason**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.application.RecordHistoryTest'`
Expected: `two gradings with identical bytes at different times are two records` FAILS — one element instead of two. The second new test fails too (one record instead of two). The five existing tests pass.

### Task A2: Resolve per `(ts, captureKey)`

**Files:**
- Modify: `src/main/kotlin/com/brokenfinger/tracker/application/RecordHistory.kt`

- [x] **Step 1: Change the KDoc paragraph and the key**

Replace the sentence `A record that changes after it was written — today only stage 3 clearing \`codePending\` — is therefore appended again rather than edited, and **the newest line for a capture key is the record**.` with:

```kotlin
 * A record that changes after it was written — today only stage 3 clearing `codePending` — is
 * therefore appended again rather than edited, and **the newest line for a `(ts, captureKey)`
 * pair is the record**. The correction is `record.copy(...)`, so it repeats both.
 *
 * ⚠️ This used to be the capture key alone, and the key is derived from the grading's bytes.
 * Gradings with no per-case timing — all of SQL, every failed compile — are byte-identical
 * whenever their output is, so distinct gradings folded into one for every reader: lesson
 * 131537 recorded 3 submits and 10 runs, `get_problem` answered 1 and 5 (#343). #159 had
 * already stopped the *writer* trusting the key as an identity; the readers still did.
```

Replace the function body:

```kotlin
    fun of(records: List<RecordedSubmission>): List<SubmissionRecord> =
        records.mapNotNull { decoded(it.line) }.associateBy { it.ts to it.captureKey }.values.toList()
```

- [x] **Step 2: Run the class**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.application.RecordHistoryTest'`
Expected: all 7 PASS.

### Task A3: The fold is gone end to end through the store

**Files:**
- Test: `src/test/kotlin/com/brokenfinger/tracker/application/RecordQueryTest.kt`

- [x] **Step 1: Add a layer test after `a problem lists a corrected attempt once`**

```kotlin
    /** #343 through the real store: identical SQL submits are counted, not folded. */
    @Test
    fun `byte-identical submits at different times are each a submission`() {
        val key = CaptureKey("dddd000000000001")
        fun submit(attempt: Int, at: String) = aSubmissionRecord(
            lessonId = 131537,
            attempt = attempt,
            ts = OffsetDateTime.parse(at),
            captureKey = key,
        )
        val query = aRecordRepository(root).containing(
            submit(1, "2026-10-03T15:25:45+09:00"),
            submit(2, "2026-10-03T15:53:58+09:00"),
            submit(3, "2026-10-03T15:55:57+09:00"),
        ).query()

        query.problem(131537).submissions.map { it.attempt } shouldContainExactly listOf(3, 2, 1)
        query.tally(TallyGroup.PROBLEM).single().count shouldBe 3
    }
```

`TallyBucket(key, label, count)` and `TallyGroup.PROBLEM` are in `domain/calc/SubmissionTally.kt`. ktlint's `max_line_length` is 120.

- [x] **Step 2: Run it, then revert Task A2 locally and run again**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.application.RecordQueryTest'`
Expected: PASS. Then `git stash` only `RecordHistory.kt`, rerun, expect FAIL (`[3]` instead of `[3, 2, 1]`), `git stash pop`. A test must be able to fail before it is allowed to pass.

### Task A4: ADR, wiki corrections, progress

**Files:**
- Create: `docs/llm-wiki/wiki/decisions/2026-10-07-a-record-is-its-time-and-its-bytes.md`
- Modify: `docs/llm-wiki/index.md`, `docs/llm-wiki/wiki/decisions/2026-08-05-code-pending-correction-append.md`, `docs/llm-wiki/wiki/decisions/2026-08-11-a-grading-is-its-whole-session.md`, `.harness/state/progress.md`

- [x] **Step 1: Write the ADR**

```markdown
---
type: decision
project: programmers-tracker
tags: [storage, jsonl, identity, mcp]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# A record is its time and its bytes

## Context

`RecordHistory` resolved the append-only log as the newest line per capture key, so that a
`codePending` correction replaces its original. The key is a digest of the grading's frames,
and since #159 the live path records byte-identical gradings as separate lines. Every reader
then folded them back into one: lesson 131537 recorded 3 submits and 10 runs, and
`get_problem` answered 1 and 5 (#343).

## Options considered

1. **A new `recordId` field** written per grading, referenced by the correction. A schema
   change, and every line written before it needs a fallback anyway.
2. **`(ts, captureKey)` as the identity.** The correction is `record.copy(...)` in
   `CodeAttachment`, so it repeats both by construction, and `ts` is taken inside the single
   writer section, so two gradings cannot share it.
3. **Make the key unique** by mixing in the time. The key must survive replay of the raw log,
   which has no write time of its own ([[decisions/2026-08-11-a-grading-is-its-whole-session]]).

## Decision

Option 2. `RecordHistory.of` keys on `ts to captureKey`.

## Rationale

It needs no schema change and no migration, and it was measured to hold on the whole live log:
242 lines in 121 `(ts, captureKey)` groups, each one grading and its correction.

## Accepted costs

- Identity now rests on a write-time clock reading. Two gradings written in the same
  nanosecond would fold; the single writer makes that unreachable, and nothing checks it.
- A future correction that is *not* a `copy` of its record would silently stop superseding.
  The unit test `a correction still supersedes the grading it repeats when the key is shared`
  pins the current shape.

## Outcome

#343. Live acceptance: `get_problem 131537` answers 3 submits and 10 runs on the rebuilt
container.
```

- [x] **Step 2: Register it in `docs/llm-wiki/index.md`** under Decisions, after the `2026-10-07-mistake-patterns-are-diagnosed-not-stored` line:

```markdown
- 2026-10-07 [[decisions/2026-10-07-a-record-is-its-time-and-its-bytes]] — Readers resolve the log per `(ts, captureKey)`; byte-identical gradings stop folding into one (#343)
```

- [x] **Step 3: Point the two older ADRs at it.** In each, append one line at the end of the ⚠️ paragraph added on 2026-10-06: `Fixed in [[decisions/2026-10-07-a-record-is-its-time-and-its-bytes]].` and set `updated: 2026-10-07`.

- [x] **Step 4: Append to `.harness/state/progress.md`**

```markdown

## 2026-10-07 — #343 the history keeps every grading (branch fix/343-history-folds-identical-gradings)
- `RecordHistory` resolves per `(ts, captureKey)`; unit + layer tests, the layer test seen failing without the fix.
- ADR [[decisions/2026-10-07-a-record-is-its-time-and-its-bytes]].
- Pending: live — `get_problem 131537` answers 3 submits / 10 runs after rebuild.
```

### Task A5: Gates, commit, PR, merge, live check

- [x] **Step 1:** `./scripts/check.sh && ./scripts/test.sh && ./scripts/build.sh` — all exit 0.
- [x] **Step 2: Commit**

```bash
git add src/main/kotlin/com/brokenfinger/tracker/application/RecordHistory.kt \
  src/test/kotlin/com/brokenfinger/tracker/application/RecordHistoryTest.kt \
  src/test/kotlin/com/brokenfinger/tracker/application/RecordQueryTest.kt \
  docs/llm-wiki .harness/state/progress.md docs/superpowers/plans/2026-10-07-records-that-are-right.md
git commit -m "fix(application): resolve the record history per (ts, captureKey)" \
  -m "Distinct gradings with identical bytes folded into one for every reader: lesson 131537 recorded 3 submits and 10 runs, get_problem answered 1 and 5. The codePending correction is record.copy, so it repeats ts and key; resolving on both keeps corrections working and stops the fold." \
  -m "Closes #343"
```

- [x] **Step 3:** push, `gh pr create`, watch every check to completion (`gh run watch <id> --exit-status`), squash-merge, delete the branch.
- [x] **Step 4: Live.** Rebuild the container; call MCP `get_problem 131537`. Expected: `submissionCount: 3`, `runCount: 10`. Record the time in the ADR's Outcome on the next branch.

---

## Part B0 — Measure before classifying (owner action, ~2 minutes)

> **Done 2026-10-07 09:28:19 KST** on lesson 59034 (attempt 2, `SELECT NO_SUCH_COLUMN FROM ANIMAL_INS`):
> the case said `passed:false`, `msg: "실패 (런타임 에러)"`, score 0.0, recorded `RUNTIME_ERROR`. A submit
> MySQL rejects does **not** say bare `실패`, so Part B proceeds as written (option 1). Frames in
> `ps-records/problems/59034-모든-레코드-조회하기/attempts/002.raw.jsonl`; this is §15 row 20.

A wrong database submit says `msg: "실패"` (273711 attempt 1, 2026-10-03). Whether a submit whose query **MySQL rejects** says the same is unmeasured. If it does, `실패` cannot be filed as WRONG without filing syntax errors as WRONG too.

- [x] **Step 1:** Ask the owner to submit `SELECT NO_SUCH_COLUMN FROM ANIMAL_INS` on lesson 59034 (Lv1, already passed — the extra failing attempt is the only cost) with the tracker running.
- [x] **Step 2:** Read the frames:

```bash
python3 - <<'EOF'
import json, glob, os
d = os.path.expanduser("~/Desktop/ps-records/problems")
f = sorted(glob.glob(f"{d}/59034-*/attempts/*.raw.jsonl"))[-1]
for line in open(f):
    m = json.loads(line).get("message", {})
    if m.get("type") in ("testcase", "result_lesson_challenge", "error"):
        print(m.get("type"), m.get("passed"), m.get("msg"))
EOF
```

- [x] **Step 3: Decide which branch Part B takes**

| Measured `msg` on the testcase | Part B |
|---|---|
| An error tuple `(1054, …)` or any text other than bare `실패` | Proceed as written: bare `실패` → WRONG, error tuple → COMPILE_ERROR |
| Bare `실패`, same as a wrong result | **Stop.** Bare `실패` stays `UNKNOWN`; only the run-path error tuple ships. Record the measurement in protocol §15 and tell the owner the submit path cannot tell the two apart |

Keep the captured frames: they become fixture `sql-submit-error.jsonl` in Task B2 when they differ.

---

## Part B — Database failures that said something

Branch: `fix/<issue#>-database-failure-verdicts` from a fresh `main` after Part A merged. Create the issue first: title `fix(domain): classify a wrong database submit and a rejected database run`, body citing 273711 attempt 1 and the two 131537 runs of 2026-10-03 15:21.

### Task B1: Fixtures from the measured frames

**Files:**
- Create: `src/test/resources/fixtures/sql-submit-wrong.jsonl`
- Create: `src/test/resources/fixtures/sql-run-error.jsonl`
- Modify: `src/test/resources/fixtures/README.md`

- [x] **Step 1: `sql-submit-wrong.jsonl`** — from `ps-records/problems/273711-업그레이드-된-아이템-구하기/attempts/001.raw.jsonl`, prefixed with the two lines every fixture starts with. Substitutions: `challengeable_id` 366 → 2778, testcase ids 845–849/1056 → 5440–5445, `finishModalLink` lesson → 131528 (dev rules §7.3). Message strings verbatim. Exact content:

```jsonl
{"type":"welcome"}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","type":"confirm_subscription"}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"submit","type":"start","testcase_ids":[5440,5441,5442,5444,5443,5445],"msg":"채점을 시작합니다.","challengeable_type":"database","challengeable_id":2778}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"submit","type":"testcase","testcase_id":5440,"passed":false,"msg":"실패","challengeable_type":"database","challengeable_id":2778}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"submit","type":"testcase","testcase_id":5441,"passed":false,"msg":"실패","challengeable_type":"database","challengeable_id":2778}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"submit","type":"testcase","testcase_id":5443,"passed":false,"msg":"실패","challengeable_type":"database","challengeable_id":2778}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"submit","type":"testcase","testcase_id":5445,"passed":false,"msg":"실패","challengeable_type":"database","challengeable_id":2778}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"submit","type":"testcase","testcase_id":5444,"passed":false,"msg":"실패","challengeable_type":"database","challengeable_id":2778}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"submit","type":"testcase","testcase_id":5442,"passed":false,"msg":"실패","challengeable_type":"database","challengeable_id":2778}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"submit","type":"result_lesson_challenge","finishModalBtnText":"다음 문제 풀기","finishModalLink":"/learn/courses/30/lessons/131528","userScore":"0.0","perfectScore":"100.0","passed":false,"challengeable_type":"database","challengeable_id":2778}}
```

Before writing it, print the source file's last line in full and confirm nothing follows `result_lesson_challenge` (the capture above was cut at 420 characters; a database submit has no `finish`, protocol §6). If a frame does follow, add it with the same substitutions.

- [x] **Step 2: `sql-run-error.jsonl`** — from the two 2026-10-03 run sessions on lesson 131537 (`ps-records/.ps/raw/recorded/20261003T062111182Z-131537.jsonl` and `…T062115911Z-131537.jsonl`), as two gradings in one stream. Substitutions: `challengeable_id` 2786 → 2778, `testcase_id` 5453 → 5437, lesson → 131528. The MySQL messages stay verbatim — they are the measurement.

```jsonl
{"type":"welcome"}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","type":"confirm_subscription"}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"run","type":"start","testcase_ids":[5437],"challengeable_type":"database","challengeable_id":2778}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"run","type":"finish","challengeable_type":"database","challengeable_id":2778,"testcase_id":5437,"returned_rows":null,"msg":"(1054, \"Unknown column 'USER_ID' in 'field list'\")","passed":false}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"run","type":"start","testcase_ids":[5437],"challengeable_type":"database","challengeable_id":2778}}
{"identifier":"{\"channel\":\"Challenge::DatabaseChannel\",\"challengeable_type\":\"database\",\"challengeable_id\":2778,\"language\":\"mysql\",\"lesson_id\":131528}","message":{"action":"run","type":"finish","challengeable_type":"database","challengeable_id":2778,"testcase_id":5437,"returned_rows":null,"msg":"(1222, 'The used SELECT statements have a different number of columns')","passed":false}}
```

- [x] **Step 3: Two rows in `src/test/resources/fixtures/README.md`**, after the `sql-run-wrong.jsonl` row:

```markdown
| `sql-submit-wrong.jsonl` | §6, §7, §15 #18 — lesson 273711, 2026-10-03: a **wrong database submit**, every case `passed:false` with the bare message `실패`, score 0.0 | `challengeable_id` 366 → 2778, testcase ids 845–849/1056 → 5440–5445, lesson in `finishModalLink` → 131528 |
| `sql-run-error.jsonl` | §6, §15 #19 — lesson 131537, 2026-10-03: two database runs **MySQL rejected**, `returned_rows:null` and the error tuple as `msg` | `challengeable_id` 2786 → 2778, `testcase_id` 5453 → 5437, lesson → 131528; the MySQL messages verbatim |
```

### Task B2: The resolver learns the two shapes (unit, zero mocks)

**Files:**
- Test: `src/test/kotlin/com/brokenfinger/tracker/domain/calc/VerdictResolverTest.kt`
- Modify: `src/main/kotlin/com/brokenfinger/tracker/domain/calc/VerdictResolver.kt`

- [x] **Step 1: Tests, after `a failure with no message that returned a result is WRONG`**

```kotlin
    /** A wrong database submit, measured on lesson 273711 (2026-10-03): the bare word and nothing else. */
    @Test
    fun `a bare failure message is WRONG`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = false, msg = "실패", runTime = null, memorySize = null)),
            boundErrorText = null,
        )

        verdict shouldBe Verdict.WRONG
    }

    /** A database run MySQL refused, measured on lesson 131537 (2026-10-03): its error tuple is the message. */
    @Test
    fun `a database error tuple is a compile error`() {
        val messages = listOf(
            "(1054, \"Unknown column 'USER_ID' in 'field list'\")",
            "(1222, 'The used SELECT statements have a different number of columns')",
        )

        messages.forEach { msg ->
            val verdict = VerdictResolver.resolve(
                testcases = listOf(
                    aTestcaseResult(
                        passed = false,
                        msg = msg,
                        runTime = null,
                        memorySize = null,
                        returnedResult = false,
                    ),
                ),
                boundErrorText = null,
            )
            verdict shouldBe Verdict.COMPILE_ERROR
        }
    }

    /** The bare word only — a failure message carrying anything after it keeps its own rule. */
    @Test
    fun `a failure message with an unmeasured suffix stays unknown`() {
        val verdict = VerdictResolver.resolve(
            testcases = listOf(aTestcaseResult(passed = false, msg = "실패 (메모리 초과)", runTime = null)),
            boundErrorText = null,
        )

        verdict shouldBe null
    }
```

- [x] **Step 2: Run, expect the first two to FAIL (`null`), the third to PASS**

Run: `./gradlew test --tests 'com.brokenfinger.tracker.domain.calc.VerdictResolverTest'`

- [x] **Step 3: Implement.** Add after `measuredMessage`:

```kotlin
    /**
     * A wrong **database submit** reports the bare word, nothing after it — no timing, because SQL
     * never sends any (protocol §6, §7). Measured 2026-10-03 on lesson 273711: six cases, each
     * `passed:false` and `"실패"`, filed UNKNOWN until this. Exact match on purpose: every other
     * failure message carries a parenthesised reason, and an unmeasured one must stay unknown.
     */
    private const val BARE_FAILURE = "실패"

    /**
     * A **database run MySQL rejected** carries the driver's error tuple as its message, e.g.
     * `(1054, "Unknown column …")`, and no table (protocol §6, measured 2026-10-03 on lesson
     * 131537). The query never ran — the same stage as a failed compile.
     */
    private val databaseErrorMessage = Regex("""^\(\d+, ["']""")
```

`BARE_FAILURE` must sit in the `object` as `private const val`; if ktlint orders properties, keep it beside the other message patterns.

In `verdictOf`, after the `measuredMessage` line:

```kotlin
        if (msg == BARE_FAILURE) return Verdict.WRONG
        if (databaseErrorMessage.containsMatchIn(msg)) return Verdict.COMPILE_ERROR
```

- [x] **Step 4: Run the class — all PASS.**

### Task B3: The mapper carries the error tuple (layer)

**Files:**
- Test: `src/test/kotlin/com/brokenfinger/tracker/protocol/parse/GradingMessageMapperTest.kt`

- [x] **Step 1: Add after the `sql-run-wrong.jsonl` test**

```kotlin
    // Protocol §6, measured 2026-10-03: a run MySQL refused — no table, the error tuple as msg.
    @Test
    fun `a rejected sql run carries the error tuple and no result`() {
        val sqlRunError = FixtureLoader.messages("sql-run-error.jsonl")

        GradingMessageMapper.testcaseOf(sqlRunError[1]) shouldBe
            aTestcaseResult(
                id = 5437,
                passed = false,
                msg = "(1054, \"Unknown column 'USER_ID' in 'field list'\")",
                runTime = null,
                memorySize = null,
                returnedResult = false,
            )
    }
```

- [x] **Step 2: Run `GradingMessageMapperTest` — expect PASS without production changes** (the mapper already sets `returnedResult = returned_rows != null`). If it fails, read the failure before touching code; a mapper change is out of this plan's scope and needs the protocol doc first.

### Task B4: End to end through the assembler (layer)

**Files:**
- Test: `src/test/kotlin/com/brokenfinger/tracker/application/GradingSessionAssemblerTest.kt`

- [x] **Step 1: Add after `a failed database run is WRONG, not unknown`**

```kotlin
    @Test
    fun `a wrong database submit is WRONG, not unknown`() {
        val session = anAssembledSession("sql-submit-wrong.jsonl", channel = aSqlChannel())

        session.action shouldBe GradingAction.SUBMIT
        session.outcome shouldBe Outcome.JUDGED
        session.verdict shouldBe Verdict.WRONG
        session.testcases shouldHaveSize 6
    }

    @Test
    fun `a database run mysql refused is a compile error`() {
        // facts() keeps broadcast frames only: start · finish · start · finish
        val firstRun = FixtureLoader.facts("sql-run-error.jsonl").take(2)

        val session = aSessionOf(firstRun, channel = aSqlChannel())

        session.action shouldBe GradingAction.RUN
        session.verdict shouldBe Verdict.COMPILE_ERROR
    }
```

`FixtureLoader.facts` keeps broadcast frames only (it filters `ActionCableFrame.Broadcast`), so `take(2)` is the first run's `start` and `finish`.

- [x] **Step 2: Run `GradingSessionAssemblerTest` — PASS.** Then revert `VerdictResolver.kt` locally, rerun, confirm both new tests FAIL, restore.

### Task B5: Protocol document

**Files:**
- Modify: `docs/programmers-protocol.md`

- [x] **Step 1: §6, under `### submit`, after its code block**

```markdown
**A wrong submit reports the bare word** (measured 2026-10-03 on lesson 273711, §15 #18): every
case `passed:false` with `msg: "실패"` and nothing after it — no timing, which SQL never sends —
then `result_lesson_challenge` with `userScore: "0.0"`. Fixture `sql-submit-wrong.jsonl`.
```

- [x] **Step 2: §6, under `### run`, after the paragraph ending ``Fixture `sql-run-wrong.jsonl`.``**

```markdown
**A run MySQL rejects carries the error and no table** (measured 2026-10-03 on lesson 131537,
two runs, §15 #19): `returned_rows: null`, `passed: false`, and `msg` is the driver's error tuple —

```jsonc
{"action":"run","type":"finish","testcase_id":5453,"returned_rows":null,
 "msg":"(1054, \"Unknown column 'USER_ID' in 'field list'\")","passed":false, …}
{"action":"run","type":"finish","testcase_id":5453,"returned_rows":null,
 "msg":"(1222, 'The used SELECT statements have a different number of columns')","passed":false, …}
```

The query never ran, which is the compile stage. Fixture `sql-run-error.jsonl`.
```

- [x] **Step 3: §7 table** — add two rows under the existing ones:

```markdown
| Wrong answer (database submit) | `"실패"` — the bare word | absent (SQL sends none) |
| Query rejected (database run) | `"(1054, \"Unknown column …\")"` — MySQL's error tuple | absent |
```

- [x] **Step 4: §15 verification log** — two rows after #17, following its column order:

```markdown
| 18 | 273711 | SQL | `submit` | **A wrong database submit, captured live** — six cases `passed:false`, `msg:"실패"` bare, score 0.0. Recorded `UNKNOWN` until the resolver matched the bare word. Kept scrubbed as `sql-submit-wrong.jsonl` |
| 19 | 131537 | SQL | `run` | **Two runs MySQL rejected, captured live** — `returned_rows:null`, the error tuple as `msg` (1054, 1222). Recorded `UNKNOWN`; now COMPILE_ERROR. Kept scrubbed as `sql-run-error.jsonl` |
```

Add Part B0's measurement as row 20 with its own result, whichever way it went.

### Task B6: ADR, wiki, progress, PR

- [x] **Step 1: ADR** `docs/llm-wiki/wiki/decisions/2026-10-07-database-failures-that-said-something.md`

```markdown
---
type: decision
project: programmers-tracker
tags: [protocol, verdict, measurement, sql]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# Database failures that said something

## Context

Two measured database failures resolved `UNKNOWN`: a wrong submit, whose cases say only
`실패` (lesson 273711, 2026-10-03), and a run MySQL rejected, whose message is the driver's error
tuple with no table (lesson 131537, 2026-10-03). The first hid every wrong SQL submit from the
verdict counts; the second was the accepted cost of
[[decisions/2026-10-01-a-failed-run-that-returned-a-result-is-wrong]], now measured.

## Options considered

1. **Classify both** — bare `실패` as WRONG, an error tuple as COMPILE_ERROR.
2. **Classify only the run error**, leaving bare `실패` unknown, if a submit MySQL rejects also
   says bare `실패` and the two cannot be told apart.
3. **Teach the resolver the problem kind.** Rejected for the same reason as in the 2026-10-01
   ADR: the message shape is the fact that matters, and it is unambiguous.

## Decision

Chosen by the measurement of an SQL-error submit (plan Part B0): record which result, and
therefore which option, here before merging.

## Rationale

Both shapes were captured live and are fixtures. Bare `실패` is matched exactly, so any
unmeasured failure with a parenthesised reason still stays unknown.

## Accepted costs

- A rejected query is filed beside compiler errors. For SQL there is no compiler; the stage is
  the same (refused before running), and the error text stays in the testcase.
- Records written before this keep `UNKNOWN`; the log is append-only.

## Outcome

Live acceptance: one wrong SQL submit and one SQL syntax-error run on the rebuilt container
show WRONG and COMPILE_ERROR.
```

Fill the Decision with the Part B0 result before the PR.

- [x] **Step 2:** register it in `docs/llm-wiki/index.md` under Decisions; append to the 2026-10-01 ADR's Outcome: `The SQL-error shape is classified in [[decisions/2026-10-07-database-failures-that-said-something]].`; add the live time to the #343 ADR's Outcome from Task A5 Step 4.
- [x] **Step 3:** progress entry in `.harness/state/progress.md` (what shipped, which branch Part B0 chose, the pending live check).
- [ ] **Step 4:** gates, commit with protocol evidence in the body (`Verified 2026-10-03 on lessons 273711 and 131537. See docs/programmers-protocol.md §6, §15 #18–19.`), push, PR, watch CI to completion, squash-merge, rebuild the container.
- [ ] **Step 5: Live.** The owner runs one wrong SQL query and submits one; `get_problem` shows `WRONG` for the submit and `COMPILE_ERROR` for a syntax-error run. Record the times in the ADR's Outcome.

---

## Self-review against the spec (part 4.1)

| Spec requirement | Task |
|---|---|
| Identity `(ts, captureKey)`, no new field, old logs resolve unchanged | A1–A3 |
| ADR amending the code-pending correction decision | A4 |
| Wrong database submit → WRONG, fixture from 273711, protocol §7 | B0, B1, B2, B4, B5 |
| Rejected database run → COMPILE_ERROR, fixtures from 2026-10-03, protocol §6 | B1–B5 |
| Error text stays in the record | Already true: it is the testcase's `msg`. No `errorText` write is added — the spec's wording is narrowed here, and plan 4.3 reads it from the testcase |
| Live acceptance `get_problem 131537` = 3 / 10 | A5 Step 4 |
