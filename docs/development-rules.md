# programmers-tracker — Coding Conventions

> **Companion to the constitution.** If [`CLAUDE.md`](../CLAUDE.md) says *what is forbidden/decided*,
> this document is the rulebook for *how to write the code*. **Always** follow it when writing or
> modifying `.kt` files.
>
> Last updated: 2026-08-04

---

## Core 5 Principles (global)

Inherited from the `~/.claude/CLAUDE.md` team standards.

1. One method = one job, max 10 lines
2. No `else`, early return
3. Wrap primitives · collections in domain objects (→ §4 VO)
4. Behavior methods over getters
5. Composition over inheritance

Kotlin files apply Effective Kotlin principles — prefer `val`, null safety, `data class`,
scope functions, `sealed class`.

---

## 1. Package Structure — location tells the role

```
com.brokenfinger.tracker/
  protocol/         ← the only place that knows the Programmers protocol
    ActionCableClient.kt
    ChannelIdentifier.kt
    message/          incoming message DTOs (protocol field names as-is)
    parse/            DTO → domain conversion
  domain/           ← knows nothing about the protocol
    Submission.kt  Verdict.kt  Attempt.kt  Tag.kt
    calc/             pure calculators (§3 Functional Core)
  application/      ← application services · Result DTOs
  adapter/
    web/              HTTP controllers (/watch for the sensor extension)
    mcp/              MCP tools · resources
    store/            file storage (JSONL · directories)
    git/              GitSync
```

Class suffixes keep the Spring convention — `XxxController` · `XxxService` · `XxxRepository`.

The dependency direction is `adapter → application → domain`, and `protocol → domain` happens only in `parse`.
**`domain` imports nothing.**

**What may cross into `application` and what may not** — see
[[decisions/2026-08-05-protocol-dependency-direction]]. The two halves behave differently
and the rule states both, because a rule stricter than its reason invites drift:

- **Identity value types live in `domain`** — `LessonId`, `ChallengeableId`, `ChannelKey`.
  `protocol` builds its wire form (`ChannelIdentifier`) from them, which is the correct
  inward direction. A change to the identifier's JSON touches `asJson()` and nothing else.
- **Protocol message types never leave `protocol`** — `SubmitMessage`, `CableEvent`,
  `ActionCableFrame` and the mappers stay there, and `protocol/parse` hands `application` a
  `GradingFrameFacts` record per frame instead — facts, not one event per message type, see
  [[decisions/2026-08-05-grading-facts-not-events]]. This is the half the rule exists for: a
  renamed message or field must not reach the verdict path.

This layout is **hexagonal (ports & adapters) with orthodox-hybrid port rules** — see
[[decisions/2026-08-05-hexagonal-architecture]]:

- **Outbound (driven) dependencies are always ports** (`RecordStore`, `GitSync`,
  `CodeFetcher`, `SessionProvider`, `RawSocket`, …) — cookie-gated integration tests
  force test doubles for all of them anyway.
- **Inbound use-case interfaces only where two consumers share the use case**
  (web + MCP). One consumer → plain application service, no interface.
- No speculative interfaces ("might need it later" is not a reason).
- Async is layered per [[decisions/2026-08-05-backend-stack]]: inbound = Spring MVC on
  virtual threads; outbound observation = coroutines + Ktor, confined to `protocol`.

---

## 2. Protocol Dependency Isolation — top-priority rule

### 2.1 Confine it to one place

No class in `domain` may know protocol field names like `testcaseId` / `testcase_id`.
Keep it so that when the protocol changes, only `protocol/parse` needs fixing.

**Rationale**: algorithm problems use camelCase (`testcaseId`), SQL problems use snake_case (`testcase_id`).
Drag this asymmetry up into the domain and every layer gets contaminated.

### 2.2 Every protocol field is nullable

This is a measured fact. Model optimistically and it blows up at runtime.

```kotlin
// ❌ measured counterexamples exist
data class TestcaseMessage(val runTime: String, val memorySize: Long)

// ✅
data class TestcaseMessage(
    val testcaseId: Long?,      // SQL sends testcase_id
    val passed: Boolean?,
    val msg: String?,           // null observed in SQL run responses
    val runTime: String?,       // null on runtime error · timeout
    val memorySize: Long?,
)
```

| Field | When it becomes null |
|---|---|
| `runTime`, `memorySize` | Runtime error · compile error · timeout |
| `msg` | SQL `run` response |
| the `finish` message itself | **SQL never sends it** |
| `scores`, `isNewRating` | All of SQL |

### 2.3 Keep what you don't recognize instead of dropping it

```kotlin
sealed interface SubmitMessage {
    data class Testcase(...) : SubmitMessage
    data class Result(...) : SubmitMessage
    data class Unknown(val type: String, val raw: JsonObject) : SubmitMessage  // required
}
```

`Unknown` leaves a warning log and preserves the original JSON as-is in the record.

### 2.4 Always preserve the original

Store only parse results and you can never reinterpret later. Keep the original incoming messages
alongside (`attempts/00N.raw.jsonl`). Disk is cheap; lost data is unrecoverable.

---

## 3. Functional Core — verdicts and aggregation as pure calculators

**Write invariant, verdict, and aggregation logic as pure classes that know no I/O.**
Input = in-memory snapshot (parameters), output = the verdict result. No file · network · git references.

Targets:

| Calculator | Input | Output |
|---|---|---|
| `VerdictResolver` | Submit messages + the preceding run record | 5 `Verdict` kinds |
| `ConfidenceCalculator` | Attempt count · hint stage · time spent | Confidence → next review date |
| `PerformanceScorer` | acceptanceRate · level · actual attempts | Performance vs. expectation |
| `StuckTestcaseFinder` | Submission history | Case numbers that failed to the end |
| `SlowPassDetector` | runTime + same-tag/level distribution | Whether the pass was slow |

**Services only assemble and store.** They gather data, hand it to the calculators, and execute
storage/commits with the results. No file reads mid-verdict — snapshot preloading is forced.

Effects:
- Mock-0 unit tests — exhaustive boundary-value verification possible
- Live capture and historical re-analysis share the same calculators → drift eliminated at the source

❌ Counterexample: inline verdict logic inside a service. Not unit-testable, not reusable from the
re-analysis path.

---

## 4. Value Objects · Separated Validation Timing

Meaningful values must not stay raw Strings → VO. `data class`, validated at creation, carries
behavior methods.

```kotlin
@JvmInline value class LessonId(val value: Long)
@JvmInline value class ChallengeableId(val value: Long)
@JvmInline value class CodesKey(val value: String)
```

`ChallengeableId` and `CodesKey` **must be different types**. If both were Long/String,
swapping them would still compile — and that confusion actually caused repeated failures
during the reverse-engineering phase (protocol doc ch. 3).

### Separated validation timing (receive-first)

**Strict at input boundaries, lenient with values received from outside.**

```kotlin
// values we create — strict. Throws on violation
fun from(raw: String): Tag

// values read from the protocol/storage — lenient. NEVER throws
fun ofReceived(raw: String?): Tag?
```

**Rationale**: if parsing throws when Programmers starts sending a new value, that submission record
is lost wholesale. Losing a record is a far greater loss than failing validation. Receive leniently,
keep it as `Unknown`, and warn.

---

## 5. Static Factory Naming

| Prefix | Meaning | Example |
|---|---|---|
| `of` | Create from values/components | `ChannelIdentifier.of(lessonId, challengeableId, lang)` |
| `from` | **Type conversion** (another type → this type) | `Submission.from(message)` · `SubmissionRecord.from(submission)` |
| `ofReceived` | Lenient creation from an externally received value (never throws) | `Verdict.ofReceived(msg)` |
| `toXxx` | This object → another type (instance method) | `submission.toRecord()` |

Prefer factories over direct constructor calls.

---

## 6. Tests

### 6.1 3-layer split

| Layer | Target | Infrastructure | Location |
|---|---|---|---|
| **Unit** | Domain models · pure calculators | **0 mocks** | `test/domain/**` |
| **Layer** | Parsers · services · controllers | Fixtures / MockK | `test/protocol/**`, `test/application/**` |
| **Integration** | Real Programmers connection | Session cookie | `test/integration/**` |

**Layer tests boot no Spring context** — plain JUnit 5 + Kotest assertions + MockK.
`@SpringBootTest` stays only in the single context-load test; Spring slice tests come later,
only for web controllers. See [[decisions/2026-08-04-test-environment]].

### 6.2 Pin measured messages as fixtures

Protocol parsers are tested with **actually captured messages**. Hand-written JSON only
verifies the protocol we imagined.

Every normal-path and verdict-path parser test **must load a `fixtures/*.jsonl` capture
through the `FixtureLoader` helper** (test `support/fixtures` package). Inline JSON literals
are allowed only for cases that cannot have a measured capture (malformed JSON, synthetic
boundary values).

```
src/test/resources/fixtures/
  algorithm-pass.jsonl        120804 · 16/16 · rating 1371→1372
  algorithm-wrong.jsonl       120803 · 1/16 · partial score 1.4
  algorithm-timeout.jsonl     120805 · timeout · 87 s
  algorithm-runtime.jsonl     120810 · runtime error
  algorithm-compile.jsonl     120820 · compile error
  sql-pass.jsonl              131528 · snake_case · no finish
  sql-run.jsonl               131528 · returned_rows double-encoded
  algorithm-run-error.jsonl   120810/120820 · run-path HTML-escaped error output
  <language>-compile-error.jsonl
                              181952 · one per supported language — java · cpp · c ·
                              kotlin · csharp · javascript, plus python indentation/tab
  kotlin-missing-main.jsonl   181952 · fun main() with no parameters — not a compile failure
```

**A language in `FileDerivedArtifacts.GENERATORS` owes a compile-failure fixture.** Its
diagnostic shape is what tells a compile error from a runtime one, and the shapes do not
resemble each other: C# brackets its position where javac and clang use colons (protocol
§7.2). Six of the seven languages were classified by two patterns written for two other
languages, four of them correctly only by coincidence, and nothing recorded which
(#212). The table in `GradingSessionAssemblerTest` is where a missing language shows up.

Each fixture is a real capture from the [protocol doc](programmers-protocol.md) ch. 15 verification log.

### 6.3 Failure-path tests required

**Cover all 5 verdicts.** Never keep only success cases.
Also test `Unknown` messages · identifier extraction failure · cookie expiry · subscription rejection.

### 6.4 Fixtures are object-mothers

Build test objects with builder functions. No repeated inline constructors.

```kotlin
// support/fixtures/SubmissionFixtures.kt
fun aSubmission(verdict: Verdict = Verdict.PASS, attempt: Int = 1) = ...
fun aTestcase(passed: Boolean = true, runTime: String? = "0.01") = ...

// usage — named-param override of only the changed fields
val timeout = aSubmission(verdict = Verdict.TIMEOUT)
```

When introducing a new domain type, add an `aXxx()` builder to `*Fixtures.kt` first and write the
tests with it.

### 6.5 Integration tests

- Disabled by default (`@Tag("integration")`), run explicitly and locally only
- The default `test` Gradle task **excludes** the tag; run them via the separate
  `integrationTest` task (`scripts/test.sh` stays unit + layer only)
- Session cookie is read from `TRACKER_SESSION_FILE` (default `~/.ps/session`);
  no session cookie → **skip via JUnit assumption — that is not a failure**
- Never run in CI
- Only against Lv0 problems (minimize impact on the account record)

---

## 7. Personal Data Handling

### 7.1 Never commit

```gitignore
.harness/state/goal.md      # personal work state
.ps/session
.ps/cookies*
*.local.yml
application-local.yml
```

Records go to the separate `ps-records` repository. No solving record of any kind enters this repository.

### 7.2 Credential masking

Session cookies live in memory only. Confine the code that handles them to a single `SessionProvider`,
and pass only the value class outward.

```kotlin
@JvmInline
value class SessionCookie(private val raw: String) {
    fun headerValue(): String = raw
    override fun toString(): String = "SessionCookie(***)"
}
```

### 7.3 Test fixture scrubbing

Use measured messages as fixtures, but substitute emails · user IDs · rankings.
Same for personally identifiable paths like `surveyUrl` · `finishModalLink`.

**Also substitute Programmers' example values** — `testcases[].input` and `.output`. They are
their data, this is a public repository, and the tests exercise the *shape* rather than the
values. **Preserve the shape exactly**: comma-joined arguments, quoting, nested brackets, and
the raw newline inside a quoted string that strict JSON rejects (protocol §7.1). The shape is
the measurement.

**Never substitute a protocol value.** The Korean result strings — `실패 (시간 초과)`,
`테스트를 통과하였습니다.` — are what verdict classification matches on (protocol §7), so
changing one makes the fixture test a protocol that does not exist. Frame types, field names,
ordering and null-ness are verbatim for the same reason.

The two look alike and are not: one is borrowed data, the other is the thing under test.
`fixtures/README.md` records which is which, so a later tidy-up does not "fix" a substituted
value back and quietly break the distinction.

---

## 8. External Dependencies Pinned as Snapshots

Data we do not control is replicated locally, and only the replica is read.

| Target | Snapshot | Refresh |
|---|---|---|
| solved.ac tag vocabulary, 229 tags | `src/main/resources/tag-vocab.json` | Manual |
| Programmers problem catalog, 689 problems | `src/main/resources/catalog.json` | **Once, ever** — see [[decisions/2026-08-06-shipped-problem-catalog]] |

**Rationale**: Baekjoon Online Judge shut down in May 2026. The solved.ac API is still alive,
but no one knows for how long.

**Even if every external service disappears, the core features (capture · record · analysis) must
keep working.**

---

## 9. Rules as a Public Repository

### 9.1 No hardcoding

```yaml
tracker:
  record-repo: ${TRACKER_RECORD_REPO:~/ps-records}
  port: ${TRACKER_PORT:1619}
  language: ${TRACKER_LANGUAGE:java}
  browser: ${TRACKER_BROWSER:chrome}
```

### 9.2 Platform dependence behind interfaces

```kotlin
interface SessionProvider { fun cookie(): SessionCookie }

class MacChromeSessionProvider : SessionProvider   // Keychain decryption
class ManualFileSessionProvider : SessionProvider  // fallback — all platforms
```

**The manual-file fallback is not optional — it is required.** The project must not become useless
in environments where automatic extraction does not work.

### 9.3 Courtesy toward Programmers

State in the README:

- This tool is **for personal learning records** and is used only on your own account
- **It provides no auto-submission** — the user submits directly in the browser,
  and the server merely observes and records the results
- The only requests the server sends are channel subscription · problem page fetch · catalog fetch,
  all at the same level as what a browser does
- Catalog polling never exceeds once per day
- If Programmers asks us to stop, we comply

> Publicly distributing a tool that uses a private protocol is different in nature from an individual
> using it on their own account. We believe there is no practical problem if the principles above are
> kept, but the final judgment and responsibility rest with the distributor. The license includes a
> disclaimer.

---

## 10. Style

- Match the ktlint rules in `.editorconfig` **at write time** (minimize after-the-fact `ktlintFormat` churn)
- Import ordering (alphabetical), chain-method-continuation, function-signature wrapping, max-line-length
- `./scripts/check.sh` must exit 0 before completion

---

## 11. Commits · Branches

- Branches: `<type>/<issue#>-<slug>` — types `feat/` · `fix/` · `docs/` ·
  `refactor/` · `test/` · `chore/` (e.g. `feat/12-actioncable-client`).
  Created by `/issue` from a fresh `main`; no `#` in branch names.
- PRs: always squash-merged to `main`; the branch is deleted on merge.
- Commit messages are English Conventional Commits
- **Protocol-related changes must leave evidence.** Cite measured results or a protocol-doc section.
  Six months later, "why did we do it this way" must be answerable.

```
fix(protocol): treat result_lesson_challenge as terminal for SQL

SQL problems never send a `finish` message; the stream ends at
result_lesson_challenge. Waiting for `finish` hangs forever.

Verified 2026-08-04 on lesson 131528. See docs/programmers-protocol.md §6.
```

---

## 12. Documentation

- `README.md` — what this solves, installation, first record within 5 minutes
- `CLAUDE.md` — the constitution (forbidden list · gates · state operations)
- `docs/programmers-protocol.md` — protocol reverse-engineering results
- `docs/development-rules.md` — this document
- `docs/superpowers/specs/` — design documents
- `LICENSE` — MIT

All committed artifacts are written in English — docs, comments, commit messages,
wiki pages, user-facing tool output. Rationale and accepted costs: see the ADR
[[decisions/2026-08-04-english-only-artifacts]].
(Reversed 2026-08-04; was Korean-first for Korean job-seekers.)
