# Progress

Entries start with the date so chronological union merges are possible.

## [2026-08-04] Phase 0 — Protocol reverse engineering ✅

Full mapping of the Programmers judging protocol. Deliverable: `docs/programmers-protocol.md` (15 sections).

- ActionCable WebSocket confirmed (not REST) — `wss://ws.programmers.co.kr:443/cable`
- Both algorithm and SQL channels verified end-to-end (solve count 90 → 92, rating 1371 → 1372)
- All 5 verdicts reproduced with measurements (PASS / WRONG / TIMEOUT / RUNTIME_ERROR / COMPILE_ERROR)
- **Passive broadcast observation verified** — a separate process received browser-fired results with only the cookie
- Confirmed absence of a submission-history API (exhaustive survey of bundle API paths)
- Acquired the solved.ac tag vocabulary of 180 tags · cross-checked tags on 210 Baekjoon problems

## [2026-08-04] Phase 0.5 — Design ✅

- Design doc `docs/superpowers/specs/2026-08-04-programmers-tracker-design.md` (13 sections)
- Development rules `CLAUDE.md` (constitution) + `docs/development-rules.md` (conventions)
- LLM Wiki structure + 3 skills
- Repository structure settled — programmers-tracker (public) + ps-records (public)

## [2026-08-04] Phase 0.7 — Record-keeping overhaul ✅

Spec `docs/superpowers/specs/2026-08-04-record-keeping-design.md` (8958fe4).

- Single authority for decision records = wiki ADRs — `.harness/state/decisions.md` retired (parity check: 5 entries confirmed as a superset)
- Push gate `.githooks/pre-push` — forces wiki changes into the push range, `Wiki-Skip:` trailer escape hatch
- SessionStart hook `.claude/hooks/inject-state.sh` — re-injects state·index (compact recovery) + idempotent hooksPath install
- 4 new ADRs · 1 raw session · global reminder guard (outside the repo, applied separately)

## [2026-08-04] Phase 0.8 — Dev workflow installed ✅

Issue-first squash-merge flow (issue #4, branch `chore/4-dev-workflow`). ADR
`docs/llm-wiki/wiki/decisions/2026-08-04-issue-first-squash-flow.md`.

- Project skills `/issue` · `/commit` · `/pull-request` (GitHub-adapted, shadow the global GitLab versions in-repo)
- CLAUDE.md: mandatory Development Flow section + 2 Forbidden entries (no direct main commits, English-only artifacts)
- development-rules §11: branch naming `<type>/<issue#>-<slug>` + chore/ prefix, squash-only PRs
- Community docs: CONTRIBUTING.md, issue forms (bug/feature), PR template, README Contributing section
- Repo settings (coordinator): labels, squash-only + auto-delete, main branch protection with `enforce_admins`

## [2026-08-04] Phase 1 — Implementation ⏳

See the implementation order in `docs/superpowers/specs/…-design.md` §11. Start from #1 (reproduce Kotlin WebSocket subscription).

### Issue #6 — Kotlin ActionCable client (`feat/6-actioncable-client`)

- ✅ Step 1/3 — Gradle Kotlin DSL project skeleton (1c7b58a)
- ✅ Step 2/3 — Protocol frame DTOs + lenient parsing (commit: see branch)
  - 8 measured fixtures extracted from protocol doc §4–§8/§15 into
    `src/test/resources/fixtures/*.jsonl` (scrubbed per dev-rules §7.3)
  - `protocol` package: `ActionCableFrame` envelope (welcome/ping/confirm/reject/
    broadcast/Unknown/Malformed), `SubmitMessage` sealed DTOs (all fields nullable,
    camelCase+snake_case, `Unknown(type, raw)` + warning log), `LessonId`/
    `ChallengeableId`/`CodesKey` value classes + `ChannelIdentifier.of` (byte-for-byte
    identifier match verified against captures)
  - 42 new tests (5 classes) — plain JUnit5 + Kotest, fixture-loaded via `FixtureLoader`
  - Gradle: default `test` excludes `@Tag("integration")`; new `integrationTest` task
  - ADR [[decisions/2026-08-04-test-environment]] (no Spring in layer tests ·
    integrationTest split · fixture-file enforcement) + dev-rules §6 amended
- ⏳ Step 3/3 — ActionCable client implemented; **live verification still OPEN**
  - `protocol` package: `ActionCableClient` (Ktor CIO glue, headers per doc §10,
    engine requestTimeout disabled for the 87 s timeout verdict §13.5) over a
    `RawSocket` seam; pure `SubscriptionProtocol` routing (welcome→subscribe,
    ping ignored, confirm/broadcast→`CableEvent` with raw text preserved,
    reject→`SubscriptionRejectedException`); `CableCommand.subscribe` byte-for-byte
    against captures; `CableEndpoint` (env-overridable §9.1 defaults)
  - Session handling: `SessionCookie` (masked toString, §7.2) + `SessionProvider`
    + `ManualFileSessionProvider` (`TRACKER_SESSION_FILE`, default project-local
    `.ps/session`; bare value or full Cookie header). Chrome auto-extraction = separate issue
  - Live entry: `./gradlew liveObserve -Pobserve="algorithm 120804 14643 java"`
    — subscribes and logs every frame with raw JSON; cookie never printed
  - 27 new tests (6 classes), fixture-driven incl. fake-socket loop tests
  - ADR [[decisions/2026-08-04-ktor-websocket-client]]; gates check/test/build all 0
  - ✅ **Live verification passed 2026-08-05**: `liveObserve` on lesson 120804
    received `confirm_subscription` (0.42 s) and all 4 broadcast frames of a
    browser-triggered run (start → testcase ×2 → result 2/2). Constitution gate
    "actually connected at least once" satisfied.
  - Found+fixed in live run: Spring Boot BOM downgraded kotlinx-coroutines to 1.8.1
    under Ktor 3.5.2 (`NoSuchMethodError` at connect) → `kotlin-coroutines.version`
    override to 1.11.0 in build.gradle.kts
  - Session file default moved to project-local `.ps/session` (+ `.ps/.gitkeep`,
    gitignore restructure) per 2026-08-05 discussion

## [2026-08-05] Backend stack + architecture settled ✅

Full-stack review with the user (session 2026-08-05). ADRs:
[[decisions/2026-08-05-backend-stack]] · [[decisions/2026-08-05-hexagonal-architecture]].

- JVM 25 + Spring Boot 4.x (3.5 hit OSS EOL 2026-06-30 — measured via EOL sources
  + Maven metadata); MVC + virtual threads inbound, coroutines+Ktor outbound only
- WebFlux rejected (MCP Streamable works on the MVC transport; ~1-5 concurrent agents)
- Architecture named: hexagonal, orthodox-hybrid ports + Functional Core, DDD
  tactical only; development-rules §1 amended
- Queued follow-up issues: ① SB 4.1.0 + JVM 25 upgrade + constitution amendment
  (stack table, Role "job-seeker" premise fix), ② Docker startup + bootstrap guide

## [2026-08-05] Design revision from the adversarial review ⏳

Issue #8, branch `docs/8-capture-design-revision`. Full adversarial review of the
communication inventory (2 inbound · 4 outbound · 3 local I/O) against measured
protocol facts. Two of my earlier claims were wrong and are corrected in-spec:
`reject_subscription` was never observed (design relied on it), and termination is an
(action × type) matrix — SQL sends `finish` on run, just not on submit.

- 3 ADRs: [[decisions/2026-08-05-capture-pipeline-stages]] ·
  [[decisions/2026-08-05-write-serialization]] · [[decisions/2026-08-05-failure-taxonomy]]
- Design §3.2 sequence: raw-append first, record on termination, CodeFetch as a late
  retryable attachment (was: recording gated on CodeFetch — one failed HTTP call
  destroyed an unrecoverable verdict)
- Design §3.3: outcome (JUDGED/INCOMPLETE/UNKNOWN) separated from verdict; bounded
  errorText promotion
- §4.1 Watcher: LRU pinning + heartbeat-ordered eviction, /watch idempotency +
  validation, subscription ≠ identifier validation, localhost bind + token
- §4.2 Capture: termination matrix, error/timeout terminal, ping watchdog + reconnect,
  testcase completeness check
- §4.3 cookie: single auth state at both boundaries; §4.4 CodeFetch: late attachment,
  codePending, accepted edit race
- §4.5 Recorder: confined single writer, JSONL as attempt authority, atomic replace,
  capture key; §4.6 GitSync: separate retryable reconciliation, path-scoped staging,
  push-scope correction, exclusive repo lock
- §5.1 layout: `.ps/raw/` → `attempts/00N.raw.jsonl`, multi-language/SQL rule;
  §5.2 record: captureKey · outcome · tcSummary.complete · rawPath · codePending
- §9 edge cases rewritten; "partial recovery" downgraded to a detector — never
  synthesize a record from the solved list

## [2026-08-05] Spring Boot 4.1 + JVM 25 + version catalog ⏳

Issue #10, branch `chore/10-spring-boot-4-jvm-25`. Driven by measured EOL: 3.5 ended
OSS support 2026-06-30 (ADR [[decisions/2026-08-05-backend-stack]]).

- Spring Boot 3.5.16 → 4.1.0, jvmToolchain 21 → 25 (verified: class-file major 69)
- `gradle/libs.versions.toml` added; plugins and dependencies use catalog aliases
- **BOM overrides survive the migration and are still load-bearing** — the 4.1.0 BOM
  pins coroutines 1.10.2 (Ktor 3.5.2 needs 1.11.0) and Kotlin 2.3.21 (our plugin is
  2.4.10). `dependencyInsight` confirms 1.11.0 / stdlib 2.4.10 after the override.
  The catalog does NOT replace this mechanism
- `spring.threads.virtual.enabled` on — inbound MVC on virtual threads per the ADR
- CLAUDE.md: stack table updated (JVM 25, Boot 4.x, layered async, catalog) and the
  stale "the user is a job-seeker" premise corrected
- Gates: check/test/build = 0, 76 tests, 0 failures
- Live re-verification ✅ complete on Boot 4.1 + JVM 25: `confirm_subscription` in 0.40 s,
  then the full browser-triggered run sequence (start → testcase ×2 → result 2/2). The
  migration did not break the Ktor/coroutines/serialization path.
  Incidental re-confirmation: testcases arrived **out of order** (index 1 before index 0),
  which is exactly why the revised design requires a completeness check rather than a sort
  alone (protocol §5, design §4.2)

### Observation worth acting on (2026-08-05)

The idle observation socket **closed silently after ~30 m 50 s** — no exception, no close
log, no reconnect; the Flow simply completed and the process exited 0. Cause is NOT
established (server idle timeout, NAT, Wi-Fi, or sleep are all candidates), so this is an
observation, not a protocol fact — do not write it into the protocol doc until a second
independent measurement reproduces it.

Two consequences:
1. It is empirical support for [[decisions/2026-08-05-failure-taxonomy]] — a long-lived
   observation socket demonstrably does not stay open, so reconnect is not optional.
2. `ActionCableClient` currently ends a session with **zero signal**. Anything broadcast
   after that point is lost forever (protocol §11) and nothing in the logs would say so.
   The Capture implementation issue must add the close/gap log and the ping watchdog.

## [2026-08-05] GitHub Actions CI ⏳

Issue #14, branch `chore/14-github-actions-ci`. Landed before implementation so the first
feature PR meets a working pipeline instead of a wall of new failures.

- `.github/workflows/ci.yml` — 3 jobs: `gates` (ktlint/test/build on ubuntu · macOS ·
  windows), `guards`, `coverage`. Concurrency cancels superseded runs; permissions are
  read-only; no event-supplied text reaches a shell
- `scripts/guards.sh` — constitution rules nothing else enforces, runnable locally:
  integration tests excluded from the default task and never invoked by a workflow;
  no tracked `.ps/` contents, records, or session-cookie-shaped strings; English-only
  scoped to Kotlin comments
- Kover added report-only. Threshold deferred until `domain/calc` exists — a gate over an
  empty package is dead config
- Negative-tested: planting a realistic cookie literal and a Korean comment makes
  `guards.sh` exit 1; a clean tree exits 0

**Guard-design note.** The first cut of the secret check fired on
`_session_production=fake-value-for-tests` in `SessionCookieTest`. Narrowed to opaque
hex/base64 runs of 24+ characters, so synthetic placeholders (which carry hyphens) pass
while a real credential or an unscrubbed fixture is caught. Similarly the English-only
check is deliberately narrow: Korean string literals are legitimate measured protocol data
(`실패 (시간 초과)`), so a blanket Hangul grep would fire on fixtures, the protocol doc and
the design doc.

## [2026-08-05] Capture — session assembly and verdict classification ⏳

Issue #16, branch `feat/16-capture-session-assembly`. Built with Orca orchestration
(3 supervised workers; A and B in parallel, C after A).

- `domain/` — Verdict(5) · Outcome(JUDGED/INCOMPLETE/UNKNOWN) · GradingAction ·
  ProblemKind · TerminalKind · TestcaseResult. Imports nothing
- `domain/calc/` — `TerminationRule` (the measured action×kind matrix, error terminal in
  every cell) and `VerdictResolver` (pure; returns null rather than coercing an
  unmeasured failure message)
- `application/` + `adapter/store/` — `RawSessionLog` port and file implementation:
  frames appended verbatim, never re-serialized; completion never overwrites; the raw
  directory doubles as the crash-recovery work list. Filenames are colon-free so Windows
  CI can create them; the Clock is injected
- `protocol/parse/` — `GradingMessageMapper` as the only wire→domain crossing, plus
  `HtmlText`; `application/GradingSessionAssembler` settles a stream into an outcome
- 156 tests, gates check/test/build/guards all 0

### Fixture gap closed with data we already had

Worker C flagged honestly that the algorithm **run** success path had no fixture, so it
was untested end to end. But we had captured exactly those frames live — twice (#6 on
Boot 3.5, reproduced in #10 on Boot 4.1). Added
`fixtures/algorithm-run-pass.jsonl` from that capture, which let three things move from
assumed to measured:

- the algorithm run cell terminates at `result`, so `SubmitMessage.Result` is now a
  first-class message rather than an `Unknown` recognised by name
- run testcases identify themselves by 0-based **`index`**, not `testcaseId` — previously
  the mapper declined them, so a run produced zero testcases and would have settled UNKNOWN
- out-of-order arrival is in the fixture itself (index 1 precedes index 0)

### Open questions left deliberately unresolved

- `VerdictResolver` lets the lowest-id failing testcase decide. Mixed failure kinds in one
  grading (case 1 wrong, case 2 timeout) have no measured precedence rule, so none was
  invented
- The memory-limit message is still unmeasured (protocol §14); the UNKNOWN test amends a
  measured timeout stream in that one field
- `INCOMPLETE` always carries a null verdict even when `result_lesson_challenge` already
  arrived — conservative, and re-derivable because the frames are preserved

## [2026-08-05] Recorder — the record write path ⏳

Issue #18, branch `feat/18-recorder-write-path`. Orchestrated (A and B parallel, C after
both). 284 tests, gates check/test/build/guards all 0.

Scope was cut along the line that matters: this PR carries only what cannot be
regenerated. `README.md`, `Solution.<ext>`, `diffFromPrev` and the runner all derive from
records, so they wait for a follow-up issue; a lost record derives from nothing
(protocol §11).

- `domain/SubmissionRecord` — the §5.2 schema with the five fields the review added.
  SQL score/rating/timing are null, never zero
- `domain/CaptureKey` — first 16 hex of SHA-256 over lessonId + action + the raw terminal
  frame text. Deterministic across restarts, because Programmers issues no submission id
- `AttemptAuthority` — restored from the JSONL only, never a directory scan. Takes the
  **highest** number per problem rather than counting lines, since runs reuse the previous
  submit's number and a torn line may already have cost one
- `JsonlRecordStore` — lenient reads, and it **heals** a torn final line on the next
  append so a crash costs one record instead of gluing two together
- `RecordLayout` — one slug rule that removes Windows-reserved characters, control
  characters and trailing dots in a single pass while keeping Korean titles readable;
  identity is the lessonId alone, so a renamed problem never splits history
- `RecordWriter` — confined single writer, attempt allocation, capture-key dedup that
  survives a restart and consumes no number, best-effort raw completion that still writes
  the record when the move fails

**Verified rather than trusted**: I mutation-tested the serialization guarantee myself —
replacing `limitedParallelism(1)` with plain `Dispatchers.IO` makes the concurrency tests
fail by losing log lines, which is the failure they are meant to catch.

### Orchestration limit found

Workers A and B touched disjoint files but share one worktree, so B's in-flight code broke
A's `./gradlew` run. A worked around it by running gates against `git archive HEAD` in a
scratch directory. **Files not overlapping does not mean builds do not overlap** — tasks
that compile concurrently need isolated worktrees or sequencing.

### Known gaps (carried to follow-up issues)

- `score`/`rating` are not extracted onto the record yet; that needs a mapper change in
  `protocol/**`, which was out of this task's scope
- Catalog, timer and diff fields sit at defaults
- A run's raw file is never moved, so `.ps/raw` accumulates until startup reconciliation
  exists. Dedup makes reprocessing safe in the meantime

## [2026-08-05] CodeFetch + the last gate-1 measurement ⏳

Issue #20, branch `feat/20-codefetch`. Built solo — a single component where orchestration
overhead would have exceeded the gain.

- `SavedCodePage` — extraction driven by a **measured excerpt of the real page**
  (`fixtures/lesson-page-saved-code.html`). Two properties of that markup break a naive
  parser and would have been absent from hand-written HTML: the `<input>` tag spans several
  lines, and `value` contains **raw** newlines. The page also carries `initial_code_<id>`,
  the untouched skeleton, so matching on `data-type="code"` is load-bearing
- `ProblemPageCodeFetcher` + `PageSource` seam (same shape as `RawSocket`) — failures are
  outcomes, not exceptions: `Unauthenticated` feeds the auth state, `RateLimited` backs off,
  a missing input is a failure rather than an empty solution. Runs after the verdict is
  already durable, so nothing it throws can unwind a record
- 290 tests, gates check/test/build/guards all 0

### gate-1 closed: `run` does save the code

Measured on lesson 120804 (protocol §15.1): baseline hash, unchanged after editing,
changed only after pressing `run`. **The CodeMirror fallback in design §4.4 is not
needed** — that removes MAIN-world injection, the most invasive part of the planned sensor
extension.

A confirming trial then eliminated the debounce hypothesis instead of leaving it open: after
a second edit the saved code stayed unchanged for three idle minutes, and any debounce short
enough to explain the first trial would have fired in that window. Remaining unmeasured:
SQL and other languages.

## [2026-08-05] Watcher — /watch and the subscription registry ⏳

Issue #22, branch `feat/22-watcher`. Orchestrated (A registry, B web adapter, in parallel).
367 tests, gates check/test/build/guards all 0.

- `SubscriptionRegistry` — LRU 8 evicting by **oldest last-heartbeat among unpinned entries
  only**; a live grading is never evicted; all-pinned returns `Saturated` and leaves the
  registry untouched so the caller fails loudly. Idempotent repeat returns `AlreadyWatching`
  and refreshes recency only
- `WatchController` — hand-parsed body accepting both the string and number form of the ids
  (the extension sends DOM `data-*`, which are strings), unknown `challengeableType`
  rejected explicitly, one error-body shape for every failure, 400/401/503, no stack trace
  and no credential echoed. Token defaults to generate-and-persist rather than off or a
  hardcoded value. `server.address` pinned to `127.0.0.1`
- `WatchService` — turns registry decisions into subscription changes; an eviction
  unsubscribes **before** the newcomer subscribes, so the cap is never briefly exceeded

**Descoped honestly**: the `ChannelSubscriber` bean is `UnconnectedChannelSubscriber`, which
logs the intent and observes nothing. `/watch` tracking is real and tested; the socket is
not attached. Issue #23 does that, and it pulls in the whole frame path
(assembler → writer, ping watchdog, reconnect, raw reconciliation).

### Architectural violation found, deliberately not fixed here

`application` imports `protocol` — `GradingSessionAssembler` (#16) and now
`SubscriptionRegistry`. Development-rules §1 states the direction as
`adapter → application → domain` with `protocol → domain` only in `parse`, so
`application → protocol` is backwards and slipped through review in #16. Worker A followed
that de-facto precedent while worker B's `WatchCommand` asserted the opposite rule, which is
how the inconsistency surfaced.

Fixing it means giving the registry a domain-level channel key and moving assembly's
protocol contact behind a port — a real refactor across two merged features. Mixing that
into this branch would violate the same constitution clause that forbids unrelated
refactoring in one PR, so it gets its own issue.

## [2026-08-05] Cable capture wired — the first end-to-end record ⏳

Issue #23, branch `feat/23-cable-capture`. **A browser click now produces a durable record
with no human step in between.** Measured three times against the real judge.

- `ChannelCapture` (worker A) — raw append first, one assembler per grading, registry pinned
  while a grading is live, bound `errorText` scoped per channel and expired once consumed
- `ConnectionLiveness` + `FileProblemTimer` (worker B) — deadline 5 x the measured 3 s ping
  = 15 s (shorter reconnects on ordinary jitter; longer will not fit detection plus backoff
  inside a grading measured at 87 s), backoff 1/2/4/8/16/30 s capped
- `CableChannelSubscriber` + `CaptureConfiguration` (coordinator) — one socket per watched
  channel on a supervisor scope, cancelled on eviction, cancelled with the context

### Three defects only running the app could find

1. **`kotlin-reflect` was missing at runtime** — present on the test classpath via
   `spring-boot-starter-test`, absent from `runtimeClasspath`. Thirteen `@WebMvcTest` slice
   tests were green while the real server threw `ClassNotFoundException` from every
   `@ExceptionHandler`, turning 401 and 400 into 500. Exactly the failure the
   "mock-only completion is forbidden" rule exists for.
2. **The problem timer was never started** — `/watch` is the only moment we learn a problem
   was opened, and nothing called `startIfAbsent`. Every record carried `elapsedSec 0`: not
   an absent value but a measured-looking zero, the same trap as filling SQL scores with 0.
3. **`tcSummary.complete` was always false for runs** — a run announces its work as the
   example testcases inline on `start` and never sends `testcase_ids`, so an id-only check
   had nothing to compare against. Systematically misleading on the most common action.
   Now checked against whichever the stream actually promised, while "promised nothing"
   still yields false — unverifiable is not verified.

### Measured end to end (lesson 120804, algorithm, java)

| Pass | Result |
|---|---|
| 1 | record written, frames preserved, testcases sorted despite arriving 1 then 0, `elapsedSec 0` (defect 2) |
| 2 | `elapsedSec 143` after wiring the timer, `complete false` (defect 3) |
| 3 | `verdict PASS`, `elapsedSec 317`, `tcSummary.complete true` |

### The guard failed twice, both times my error

First it fired on every `--tests` filtered run, naming 43 innocent classes, so it became an
explicit task instead of a `finalizedBy`. Then CI failed on all three operating systems with
**all 44** classes named: the task had no dependency on `test`, so it read an empty results
directory. It had passed locally only because a previous run's result files were still
lying there — a stale-state false pass, which is precisely the class of defect the task
exists to catch, committed by the task itself.

Now `dependsOn(tasks.test)`, and verified from a genuinely clean state
(`clean` then `--no-build-cache`) rather than against a dirty workspace.

### Also fixed: a guard that cried wolf

`verifyEveryTestClassRan` failed on any `--tests` filtered run, naming 43 innocent classes.
Filter detection through Gradle internals did not work, so it became an explicit task run by
`scripts/test.sh` and CI after a full suite. A guard that fires on correct usage gets
disabled, and then it defends nothing.

### Still open

- Ping watchdog and reconnect are **built but not attached** — `ConnectionLiveness` has no
  caller yet. The socket still dies silently after ~30 minutes
- Startup reconciliation of orphaned `.ps/raw` sessions
- `title` is empty on records: no catalog source is wired, and an empty string is honest
  where a placeholder would not be

## [2026-08-05] Watchdog, reconnect, and startup reconciliation ⏳

Issue #27, branch `feat/27-watchdog-reconnect`. 451 tests, gates all 0.

- `CableChannelSubscriber` now **retries observation until the channel is unsubscribed**, and
  treats three endings identically: an exception, a flow that simply completes (the measured
  ~30-minute silent close), and silence past the deadline. Each reconnect logs the gap
  loudly — a silent gap is the failure this exists to prevent
- `ChannelCapture.connectionLost()` settles an in-flight grading as INCOMPLETE with frames
  kept, rather than waiting for a result that is never re-sent
- `RawSessionReconciler` + `StoredChannel` (worker) — startup picks up whatever a crash left
  in `.ps/raw`; wired as an `ApplicationRunner`
- Backoff is injected in tests, so reconnection is observed by counting attempts rather than
  by spending 30 seconds waiting for one

### Measured protocol fact found while building recovery

Across all nine fixtures: an algorithm **submit**'s inner messages carry no
`challengeable_type` and no `language` (language appears only on
`result_lesson_challenge`), while algorithm **run** and all SQL messages do carry the type.
So the envelope `identifier` is the **only** source of problem family and language — in
exactly the crash-mid-grading case recovery exists for. Recorded as protocol doc §15.2, and
`ChannelIdentifier.asJson()` gained a round-trip-tested inverse.

### Worth noting about the dedup proof

The worker proved duplicate-freedom by **delegation rather than a second mechanism**: it
reconciles once, asserts the session is still on the work list (so the second pass is not
vacuous), then reconciles again with a freshly constructed `RecordWriter` that rebuilt its
key index from the log on disk — the actual restart case — and gets zero new records.

### CI broke twice on this branch, both times on my test design

**ubuntu**: `silence beyond the deadline` asserted after a fixed 250 ms wait against a 60 ms
deadline. Fine on an idle laptop, flaky on a loaded runner. Now it waits for the behaviour
(polling a counter under a 10 s ceiling) instead of sleeping a guess — the timeout only
bounds a genuine failure.

**windows, cancelled past ten minutes**: worse and entirely mine. `waitFor = {}` removed the
backoff, so against a flow that ends immediately the retry loop became a **busy spin** — and
each test built a `CoroutineScope` it never cancelled, so those spins outlived their tests
and burned CPU for the rest of the suite. Five such tests, one runner pinned.

Two rules now stated in the test class itself: **await the behaviour, never sleep for it**,
and **cancel every scope** when the thing under test loops forever by design. Verified by
running the class three times (stable) and the full suite in 5 s.

## [2026-08-05] Identity value types move into `domain` ⏳

Issue #24, branch `refactor/24-protocol-dependency-direction`. Pure refactor — 451 tests
before and after, no behaviour change, all gates 0.

Decision 1 of [[decisions/2026-08-05-protocol-dependency-direction]] only. The ADR splits
the seven `application → protocol` imports into two kinds and lands them separately, because
the message half touches the verdict path and must not be reviewed inside a rename diff.

- `LessonId` and `ChallengeableId` moved to `domain`, keeping their validation and the §3
  trap rationale (challengeable id vs codes key — four consecutive reverse-engineering
  failures). `CodesKey` stays in `protocol`: nothing above it uses it
- New `domain/ChannelKey` — lesson, challengeable, `ProblemKind`, language. This is the
  identity everything above `protocol` now keys subscriptions, registries and captures by
- `protocol/ChannelIdentifier` is the **wire form**, built from a key via
  `ChannelIdentifier.from(key)` and exposing `key`. `asJson()` is unchanged, which the
  byte-for-byte test and the round-trip over all nine measured captures both still prove
- `ChallengeableType` stayed in `protocol` — it is wire mapping, not identity — and gained
  the `ProblemKind → ChallengeableType` direction the identifier needs
- Zero identity imports of `protocol` remain in `application`. What remains is exactly the
  message half (`SubmitMessage`, `CableEvent`, `ActionCableFrame`, `GradingMessageMapper`,
  `StoredChannel`), which is issue #29

### Comments that had gone stale in the process

Four KDocs justified a design by "the value class lives in `protocol`" — `WatchCommand`,
`WatchRequestHandler`, `RawSessionLog`, `SubmissionRecord`. The types they name are right
where they should be; the reasons were rewritten rather than deleted, since each decision
(plain `Long` in the serialized record, plain `Long` as a directory name) still holds for a
different reason than the one recorded.

### Still open

- Issue #29: `protocol/parse` must hand `application` domain-level grading events, so a
  renamed message or field cannot reach `GradingSessionAssembler`. The violation that
  matters most is the one that survives longest

## [2026-08-05] Protocol dependency direction settled ⏳

Issue #24, branch `refactor/24-protocol-dependency-direction`. ADR
[[decisions/2026-08-05-protocol-dependency-direction]].

Counting the imports split the disagreement in two, which is what made it decidable:

- **Identity** (`ChannelIdentifier`, `LessonId`, `ChallengeableId`, `ChallengeableType`),
  9 imports — does **not** violate what §2.1 protects. A change to the identifier's JSON
  touches `asJson()` and nothing in `application`
- **Messages** (`SubmitMessage`, `CableEvent`, `ActionCableFrame`, the mappers), 9 imports —
  violates it squarely: a renamed message reaches `GradingSessionAssembler`, i.e. the
  verdict path

So the rule was right about messages and over-strict about identity, and both workers who
disagreed in #22 were each half right about a codebase that was inconsistent.

**Landed here (decision 1)**: `LessonId`/`ChallengeableId` moved to `domain`, new
`domain/ChannelKey` is the identity, `protocol/ChannelIdentifier` is the wire form built
from it. `asJson()` output confirmed unchanged by the byte-for-byte test and the
`StoredChannel` round-trip over all nine captures — it is the ActionCable broadcast key, so
a changed byte breaks subscription silently. 451 tests before and after.

**Split out as #29 (decision 2)**: messages reach `application` as domain events. Kept
separate on purpose — it touches verdict resolution, and inside a rename-heavy diff a
reviewer could not see it.

## [2026-08-05] Messages reach `application` as domain facts ⏳

Issue #29, branch `refactor/29-domain-grading-events`. ADR
[[decisions/2026-08-05-grading-facts-not-events]] — the second half of
[[decisions/2026-08-05-protocol-dependency-direction]], and the half that protects the
verdict path.

The shape changed from what #24 assumed. "Domain grading events" reads as a sealed hierarchy,
but one frame contributes several **orthogonal** things at once — an algorithm `start` names
the action, announces a count and opens the grading; a database `finish` ends the stream and
carries the only testcase there is. An event per frame would have mirrored `SubmitMessage`
one-to-one, i.e. the same coupling with a domain name on it. So the crossing is a record of
extracted facts:

- `domain/GradingFrameFacts` — action · terminal kind · testcase · announced ids · announced
  count · error text · starts-a-grading. `GradingMessageMapper.factsOf` builds it; the six
  per-fact mappers became its internals and stayed the units the fixture tests drive
- `application/ObservedFrame` — wire text + facts, so stage-1-before-interpretation stays
  literally true while `ChannelCapture` names no wire type
- `application/FrameReader` port ← `protocol/parse/ObservedFrames`. The reconciler replays
  through it, `channelOf` included: the envelope is still the only place an algorithm
  submit's family and language survive (protocol doc §15.2), so that parsing stayed down

`GradingSession.frames` holds the facts, not the wire text. The verbatim original is on disk
before the session settles and the record points at it, so §2.4 is satisfied by bytes that
outlive the process; a second in-memory copy would only be a weaker archive to mistake for
the real one. Cost recorded in the ADR: the list now proves how many frames were
uninterpreted, not which.

**Measured**: zero `protocol` imports remain under `application` — production *and* tests,
not just `message`/`parse` — and 451 tests before and after, the same captures driving the
same five verdicts. check · test · build · guards all exit 0.

## [2026-08-05] Protocol messages stop at the boundary ⏳

Issue #29, branch `refactor/29-domain-grading-events`. Decision 2 of
[[decisions/2026-08-05-protocol-dependency-direction]]. 451 tests before and after, gates 0.

**`application/` now imports nothing from `protocol` at all** — identity went in #24,
messages here. A renamed Programmers message can no longer reach verdict resolution.

- `domain/GradingFrameFacts` — the seven orthogonal facts one frame contributes. The worker
  argued against a sealed event hierarchy and was right: one frame carries several at once
  (an algorithm `start` names the action, announces a count, and opens the grading), so an
  event-per-frame type would have mirrored `SubmitMessage` one-to-one and kept the coupling
  under a new name. ADR: [[decisions/2026-08-05-grading-facts-not-events]]
- `application/ObservedFrame` (wire text + facts) keeps stage 1 intact — the raw append still
  happens before any interpretation, verified in `ChannelCapture`
- `GradingSession.frames` holds facts rather than messages, justified in its KDoc: the
  verbatim original is already on disk before a session settles, so a second in-memory copy
  would be a weaker archive easy to mistake for the real one

The ADR's honest note is worth keeping: `protocol/parse` imports `application` for the port,
as `ProblemPageCodeFetcher` already does — consumer-declared ports are the shape here, but
"`protocol` imports nothing upward" was never literally true.

## [2026-08-05] CI hardened to stand in for a reviewer ⏳

Issue #32, branch `chore/32-harden-ci`. Landed **before** unattended self-merging starts,
because merging without a human reading the diff makes CI the only reviewer, and three of
this project's failures so far were things a green build did not catch.

- **`verifyCalculatorCoverage`** — branch coverage of `domain/calc` must hold 95%; it is at
  100% today. Read from the Kover XML rather than a Kover rule, because Kover's verification
  rules cannot be narrowed to one package and a project-wide number measures the wrong thing.
  Negative-tested both ways: raising the threshold fails with the real number, and renaming
  the package fails with "was it renamed, or did its tests stop running?"
- **`repeatability` job** — runs the suite three times with `--no-build-cache --rerun-tasks`.
  Two of the three past failures were non-deterministic and passed locally every time, and a
  cached `test` task once made a broken guard look green. Each attempt prints its duration,
  so a test that leaks work shows up as a slowing suite rather than as nothing at all
- Simulated locally before pushing: three clean runs at 13 s / 8 s / 7 s

The threshold is deliberately not a target to optimise — it exists to catch an unexercised
branch in the code that decides verdicts.

## [2026-08-05] Recorder derived artifacts ⏳

Issue #34, branch `feat/34-derived-artifacts`. 483 tests, gates all 0, calculator coverage
still 100%.

- `CodeArtifacts` — `Solution.<ext>` refreshed on both run and submit; `attempts/NNN.<ext>`
  only for a submit that owns a number; a hand-written LCS unified diff against **the
  previous attempt in the same language**, selected from the submission log because that is
  the single authority and because `mysql` and `oracle` share the `.sql` extension. Returns
  null rather than diffing against nothing for a first attempt, a missing file, or unchanged
  code — a diff against an empty file would report the whole solution as added. Capped at
  400 lines, and inputs over 2000 lines yield null instead of an expensive table
- `ProblemReadme` — overwritten whole every time, byte-identical across regenerations, and
  proven by test to leave `notes.md` untouched. **Missing data degrades honestly**: with
  today's records the title is empty, so the frontmatter key is omitted entirely and the
  heading falls back to the lesson id. It never invents a name

### Not done, and not claimed

- **Nothing calls these yet.** Writing `Solution.<ext>` needs the fetched code, and stage 3
  (CodeFetch → attach) is still unwired — `codePending` is `true` on every record. Follow-up
  issue: wire the attachment and then these generators
- **The runner generator is not built.** It needs the example testcases the `run` `start`
  frame carries inline, and those are not currently carried through to the record. Its own
  issue rather than a guess

## [2026-08-05] GitSync for the record repository ⏳

Issue #39, branch `feat/39-gitsync`. 509 tests (26 new), gates all 0, calculator coverage
still 100%.

- `application/GitSync` — outbound port whose contract is that **nothing throws** and every
  method is safe to call again: `commitSubmission` (one submit, one commit, path-scoped),
  `reconcile` (commit whatever is uncommitted, no-op on a clean tree), `push`
- `adapter/git/CommandLineGitSync` — the git CLI, not JGit. Retries **only** `index.lock`
  contention (5 attempts, 100/200/400/800 ms, injected `waitFor`); every other failure
  returns at once with git's own words in the log and waits for the next reconciliation.
  ADR: [[decisions/2026-08-05-git-retry-scope]]
- `adapter/git/CommitMessage` — design §4.6 subject, degrading honestly: no level drops the
  `[LvN]` bracket, an empty title falls back to the lesson id as `ProblemReadme` does. That
  is the normal case today, not an edge one
- Tested against **real temporary repositories** and a local bare remote — a hand-made
  `.git/index.lock` proves the retry (two attempts fail, the lock is deleted inside the
  injected wait, the third succeeds), and a `notes.md` left both dirty and staged proves the
  commit carries only its own paths. Mutation-checked: dropping the pathspec, the run guard
  or the contention branch fails exactly the four tests that claim them

### Not done, and not claimed

- **Nothing calls it.** No caller commits a record yet; wiring belongs with stage 3
- **The 23:00 backup run is not scheduled.** The entry point (`reconcile` + `push`) exists,
  the schedule does not — deliberately out of scope for this issue
- **Never run against a real record repository**, only temporary ones

## [2026-08-06] GitSync ⏳

Issue #39, branch `feat/39-gitsync`. 509 tests, gates all 0, calculator coverage 100%.

- Outbound `GitSync` port with a git-CLI implementation and the §4.6 commit subject
- **Retry scope decided and recorded** ([[decisions/2026-08-05-git-retry-scope]]): only
  `index.lock` contention is retried. A non-fast-forward, a directory that is not a
  repository and a pathspec matching nothing cannot heal by waiting, and retrying them
  spends a capture's time pretending they might
- Contention proven with a hand-made `.git/index.lock`: two attempts fail, the lock is
  removed from inside the injected wait, the third succeeds. A lock that never clears gives
  up without throwing
- Path-scoped staging proven the hard way — `notes.md` left both dirty **and staged by
  another actor**, and the submit commit still contains only the solution file while
  `notes.md` stays staged. An editor's git integration stages files exactly that way
- Not wired: nothing calls it yet, and the 23:00 backup scheduler is deliberately out of
  scope; `reconcile()` and `push()` are exposed

### Guardrail refined mid-run

"Never merge a change to an ADR" turned out to be unworkable: the wiki push gate requires
every branch with decisions to carry one, so the rule would block everything. The
distinction that actually matters is **contract change versus elaboration** — #36 is held
because it changes what `submissions.jsonl` means, while #39 merges because its ADR fills in
retry semantics inside a decision already accepted.

## [2026-08-06] GitSync wired into the pipeline ✅

Issue #41, branch `feat/41-wire-gitsync`. 537 tests (28 new), gates all 0
(`check.sh` · `test.sh` · `build.sh` · `guards.sh` · `verifyCalculatorCoverage` = 100%).
ADR: [[decisions/2026-08-06-wire-git-into-the-pipeline]].

- **A settled submit is committed where it is written.** `RecordWriter` calls
  `GitSync.commitSubmission` inside the same `withContext(writerDispatcher)` section as the
  append — one derived write, one index, one writer. A `run` is not committed on its own
- **Proven serialized the way the append is**: `RecordWriterSerializationTest` now routes the
  append and the commit through one in-flight counter and drives 64 concurrent settlements
  through it. Peak occupancy 1, so no commit ever overlaps another grading's append
- **A git failure never costs a record.** A `GitSync` that throws on every call still leaves
  the record on disk and returns it, and the next `reconcile()` commits what was left. The
  writer's `runCatching` exists specifically to protect the dedup key, which `write()` removes
  when the body throws
- **`StartupReconciliation`** sequences the boot recoveries in order — raw sessions become
  records, `reconcile()` commits whatever is uncommitted, then the backup catches up. Safe to
  repeat; three runs leave one commit
- **The 23:00 Asia/Seoul backup, with catch-up** — `DailyBackup` compares an injected clock
  against an instant persisted through `AtomicStateFile` in `.ps`, so "the machine slept
  through 23:00" is a fixed clock and an assertion rather than a wait. `BackupSchedule` ticks
  once a minute and asks; no cron expression, so the hour is spelled once
- **A records directory that is not a repository** is detected once via `git rev-parse`, said
  once, and skipped for the life of the process — proven by a log assertion and by `git init`
  after detection, which is deliberately not noticed
- Every test drives real temporary repositories and a local bare remote. No mocks, no network,
  no sleeps

### Not done, and not claimed

- **The derived artifacts of #34 are still not in the commit** — solution file, diff and
  README have no producer yet (#36), so a submit commit carries the log and the raw frames only
- **The MCP `push()` trigger has no caller**, because MCP does not exist yet
- **Never run against a real record repository**, only temporary ones. The Spring context test
  now redirects every path into a scratch directory: booting runs the startup reconciliation,
  and `git add --all` against a developer's own `~/ps-records` would commit their pending work

## [2026-08-06] GitSync wired ⏳

Issue #41, branch `feat/41-wire-gitsync`. 537 tests, all gates 0, calculator coverage 100%.
ADR [[decisions/2026-08-06-wire-git-into-the-pipeline]].

Written specifically to stop a pattern: this was the third capability in a row to land with
no caller (artifacts #34, GitSync #39, and `ConnectionLiveness` before it). Unwired code is
indistinguishable from working code until someone looks.

- `RecordWriter` commits inside the same `withContext(writerDispatcher)` section as the
  append — a second writer beside the confined one is exactly what that decision prevents
- `StartupReconciliation` sequences raw-session recovery, then `git.reconcile()`, then the
  backup catch-up. **The order is load-bearing**: reconcile first and it misses the records
  the sessions were about to write
- `DailyBackup` compares an injected clock against a persisted instant, so a 23:00 slept
  through is caught up at the next start. Only a push that landed is recorded, so a failed
  one leaves the day due
- A fresh install with no git repository is detected once and skipped thereafter — failing
  every commit forever would bury every other message

### The subtle one the worker found

The writer guards the git call with `runCatching` even though the port promises not to throw
— not defensive habit. The record is already durable at that point, so an escaping exception
would take the **capture key** down with it (`write()` removes the key when its body throws)
and a reconnect replay would then record the same grading twice.

Not wired and not claimed: the #34 artifacts still have no producer, so a submit commit
carries only the log and the raw frames; nothing has run against a real record repository.

### The Windows failure on #41, and what it really was

CI failed on Windows only. The message was worth reading in full rather than guessing at:

```
Illegal char <:> at index 16: C:\Users\RUNNERC:\Users\runneradmin1\AppData\Local\Temp\/...
```

The configured path was a Windows temp directory, and Windows temp directories are 8.3 short
paths: `C:\Users\RUNNER~1\AppData\Local\Temp`. Home-directory expansion used
`replaceFirst("~", home)`, which rewrites a tilde **anywhere** in the string — so it spliced
the home directory into the middle of the path.

Two defects in one line, both mine, both invisible on macOS and Linux:

- `replaceFirst` matched a tilde that was not a home marker
- its replacement is a regex replacement, where a backslash is an escape character, and every
  Windows home directory is full of them

Fixed with a single `ConfiguredPath.of` used by all three call sites (two configurations had
copied the same line, and `ManualFileSessionProvider` had it too), and a test whose
Windows-short-path case fails on the old implementation — verified by restoring it.

Third time the three-OS matrix has paid for itself, and the second time the real cause was
only readable from the uploaded test report rather than the job log.

## [2026-08-06] Runnable image + bootstrap guide ✅

Issue #43, branch `chore/43-docker-bootstrap`. 542 tests, all gates 0, calculator coverage 100%.
ADR [[decisions/2026-08-06-container-network-posture]].

The README promised a first record in five minutes and nothing delivered it — a new user had
to infer the whole setup from source. Now: `Dockerfile` (multi-stage, JVM 25, nothing
user-specific baked in), `compose.yaml` (records and `.ps` bind-mounted, cookie as a
read-only compose secret), `.env.example`, and `docs/bootstrap.md`.

`docs/bootstrap.md` rather than a README section, and the README got a six-line quickstart
that links to it. The README's job is "should I use this"; a walkthrough covering cookie
extraction, the watch token, uid mapping and git credentials is 200 lines and would bury the
pitch it sits in front of.

### The finding: a bind address in a container is not the control it looks like

`application.yml` binds `127.0.0.1` and says why — this process holds a live session cookie
and can push to GitHub. Carried into a container that mechanism inverts: a container has its
own network namespace, `-p` forwards to its **eth0**, never to its loopback, so a faithfully
loopback-bound container is unreachable from the browser extension that is `/watch`'s only
caller. It would boot, pass a health check, and be useless.

Resolved by restating the property instead of applying the rule: the property is "not
reachable from the LAN", and in a container the **publish** address delivers it. compose
binds `0.0.0.0` inside the namespace and publishes `127.0.0.1:8080:8080`. The application
default is untouched, so native runs stay loopback and the `0.0.0.0` never leaves the one
file where the loopback publish makes it safe.

The rejected option is the interesting one. Keeping the loopback bind and telling users to
set `TRACKER_BIND_ADDRESS=0.0.0.0` themselves *looks* safer, but it puts that setting on the
happy path of the getting-started guide — where users would learn it and carry it to a native
run, which genuinely does open `/watch` to the LAN. Documentation teaches.

CI asserts the distinction rather than describing it: the docker job starts the image twice,
both published to host loopback, and requires the default-bind container to be **unreachable**
and the `0.0.0.0` one reachable. A compose comment cannot fail a build.

### Verified by running it, not by reading it

Boots in ~1.0 s; `/` → 404, `/watch` untokened → 401, healthcheck `healthy`; the `.ps` mount
receives `watch-token` at `rw-------`; the compose secret is genuinely read-only (write
refused); git resolves an identity against `/records` at a foreign uid, so the system-level
`safe.directory` works. Two defects the run caught that reading would not have: the temurin
base already owns uid 1000 (`useradd` exits 4), and a numeric `user:` override leaves `HOME`
at `/` so every mounted credential goes unread — hence the explicit `ENV HOME`.

### The exclusive record-repository lock does not exist

[[decisions/2026-08-05-write-serialization]] decision 5 says the record repository is locked
exclusively at startup, and names "container plus a local run" as the double-writer it exists
for. There is no `FileLock` or equivalent anywhere in `src/main/kotlin` — the decision was
recorded and never implemented. Shipping a container makes the predicted scenario trivially
reachable, so the gap is now larger than when it was written. Out of #43's scope, stated in
the ADR and in the guide's "what you cannot do yet" rather than papered over, and filed as
**#44** so it is tracked rather than merely noted.

Also still missing and now stated plainly for users: no browser extension exists in this
repository, so problems must be registered by hand with a `curl` to `/watch` — documented,
including the DevTools snippet that produces the body.

## [2026-08-06] The exclusive record-repository lock ⏳

Issue #44, branch `fix/44-record-repo-lock`. 559 tests, all four gates 0.
ADR [[decisions/2026-08-06-record-repository-lock]].

[[decisions/2026-08-05-write-serialization]] decision 5 had asserted since 2026-08-05 that
the record repository is locked exclusively at startup. Nothing implemented it, so the wiki
claimed a safety property the code did not have — and #43 shipped the container that makes
"compose up while bootRun is alive" a two-line accident.

Now `RecordRepositoryLock`: `FileChannel.tryLock`, taken during bean instantiation and held
for the process lifetime, with the refusal rendered as Spring Boot's APPLICATION FAILED TO
START block rather than a stack trace. Both of `tryLock`'s refusals are handled — `null` for
another process, `OverlappingFileLockException` for the same JVM — because handling only the
first makes same-JVM tests pass while proving nothing.

### The placement is the design

`CommandLineGitSync.reconcile` is `git add --all`. A lock file at the record-repository root
would be committed and pushed to a public records repository, so the lock lives in `.git/`,
which git never tracks. A records directory with no `.git` falls back to the root — nothing
there can commit it — and the window that leaves (a run, then `git init`, then a run) is shut
from both ends: the root file is deleted once a `.git` exists, and the name is in the
template `.gitignore`. The test asserts on what git actually committed, not on the ignore rule.

### The measurement that changed what this issue delivers

Four probes, each a real second process running the real boot jar:

| Case | Second instance |
|---|---|
| Two native JVMs, same records directory | refused, exit 1, no Tomcat, formatted block |
| Two processes in one container, records on overlayfs | refused |
| Two processes in one container, records on a host bind mount | **started** |
| Host JVM + container, same host bind mount | **started** |

**Docker Desktop for macOS does not honour POSIX record locks on a bind mount**, and it fails
silently — `tryLock` returns a lock that excludes nobody, with no `IOException`, so neither
the refusal nor the escape hatch fires. The overlayfs case proves the JVM and the Linux side
are fine; the boundary is the virtualised filesystem. The headline scenario on macOS is
therefore still unprotected, which `docs/bootstrap.md` now says in those words instead of
implying the lock fixed it. A native Linux host plus a container is expected to work for the
same reason overlayfs does and is **unverified** — nothing here could test it.

### A test wrote to `~/ps-records`, which is a user's real record repository

Found by the coordinator during this issue, not by the suite. The new Spring-context test
configured its paths with `SpringApplication.setDefaultProperties`, which sits *below*
`application.yml` in Spring's precedence order — so every override was silently ignored, the
context booted against the shipped default `~/ps-records`, and it created that directory in a
home directory. On a real machine that is a solving history reconciled with `git add --all`.

Fixed with command-line arguments (`--tracker.record-repo=…`), and the class is now guarded
rather than the instance: `scripts/no-home-writes.sh` runs a command and fails if a
home-directory default appeared, wired into the CI gates job on all three platforms. Proved
by reintroducing the bug once and watching it fire. The test also asserts
`refused.recordRoot == records`, so a boot that reaches a different repository can no longer
pass.

### Still open

The gap the measurement found. Closing it needs a mechanism that survives a filesystem with
no locks — a heartbeat marker aged out by mtime is the candidate — and that is a separate
decision, not a quiet addition to this one.

## [2026-08-06] LICENSE — the file three documents already cited ✅

Issue #48, branch `chore/48-license`. Merge of #44 is `43bde0f`.

There was no `LICENSE`. `find . -iname 'license*'` returned nothing and GitHub reported none,
while the README ended with a "## License / MIT" section, development-rules §12 listed
`LICENSE — MIT` among the project's documents, and §9.3 rested part of the
public-distribution argument on *"the license includes a disclaimer."*

Without the file the repository is **all rights reserved** by default: every reader following
"Issues and PRs are welcome" would be contributing to something they had no rights to use,
and anyone cloning it had no permission to run it — the opposite of what a public tool means.

Standard MIT text, unmodified. Modifying it would break license detection and stop it being
MIT in any useful sense, and the standard "AS IS, WITHOUT WARRANTY OF ANY KIND" clause is
exactly the disclaimer §9.3 relies on. The private-protocol caveat is a usage point rather
than a warranty one and already lives in the README's principles section, so the README's
License heading now links the file and says which document carries which.

Copyright holder is the GitHub handle. Swap it for a legal name if preferred — it is a
one-line edit and nothing depends on the string.

## [2026-08-06] The README claimed features that do not exist ⏳

Issue #47, branch `docs/47-honest-readme`. Merge of #48 is `cdcecbc`.

Nine claims in the README were false against `src/main/kotlin` — MCP in the opening sentence
and again in the comparison table, saved failing code and attempt diffs (`CodeFetcher`,
`CodeArtifacts` and `ProblemReadme` are all written and none is wired), the sensor and MCP
boxes of the architecture diagram, all seven analysis bullets, a catalog fetch that does not
exist, `.claude/commands/` after the directory became `.claude/skills/`, and a structure block
saying the server creates the record repository four sections after *Get started* has the user
create it. Every entry in the issue's table was re-verified against the tree; all nine held.

### The shape, not the wording

The banner was never the problem. Status was asserted in **nine places**, so keeping the
document true needed an audit rather than an edit — which is why all nine drifted. Now
`README.md` carries build status in exactly one section, *What works today*, as a table of
capability → `built` / `designed · §n`. Everything after it is written in design tense: the
architecture diagram is captioned as the design, the comparison table compares what the two
tools are built to do, and the analysis bullets are *what the record is designed to tell you*.
None of them needs editing when a feature lands.

The test is #46, queued behind this: it flips one cell from `designed · §7` to `built`.

`CONTRIBUTING.md`: **JDK 21 → 25** (a contributor following it failed the build before writing
a line), the job-seeker line replaced with the maintainer's time budget, the #50 warning that
renaming a CI job leaves `main` unmergeable, and the documentation rule as a contributor
obligation. `CLAUDE.md` deliberately untouched — the constitution is the owner's call.

### The guard, and the two traps that shaped it

`scripts/guards.sh` check 4: every repository path a **maintained** document names must exist.
It would have caught `.claude/commands/` and the missing `LICENSE` the day each was written.

Both narrowings came from measurement rather than taste. An unanchored scan of fenced blocks
over all 67 tracked Markdown files finds 190 path-shaped strings, **121 of which do not
exist — 64% false positives**, because tree diagrams are relative to something (a Java package,
a problem directory, `ps-records/`). The fix is one rule: a block is walked only when its first
line names a directory this repository actually has. And the corpus is mostly *dated records* —
`docs/llm-wiki/raw/` is declared immutable — so a guard that walked them would eventually
demand an edit we promised never to make. Scope is `README.md`, `CONTRIBUTING.md`, `CLAUDE.md`,
`docs/*.md`.

Under those rules, over all 67 files: **34 paths judged, 0 false positives.** Existence is
decided from git's index rather than the working tree, so a dirty workspace cannot make it
pass. `guard:planned` opts out one line.

Proved by making it fail, from a fresh clone of the branch rather than the working tree:
`.claude/commands/` fires, a broken `[MIT](LICENCE.md)` fires, the same path marked
`guard:planned` passes, and `~/ps-records`, URLs, anchors and `<placeholder>` paths never fire.

### Remaining

The semantic half is not mechanised and deliberately not faked with a keyword list — "the
README says MCP works and MCP does not" stays a review responsibility. That is the argument
for the single table.

## [2026-08-06] The MCP read slice — the expose half finally exists ⏳

Issue #46, branch `feat/46-mcp-read-slice`. ADR
[[decisions/2026-08-06-mcp-read-slice]]. `docs/mcp.md` is the user-facing page.

`POST /mcp`, Streamable HTTP, three read-only tools — `submissions(since?, verdict?)`,
`get_problem(lessonId)`, `stats(groupBy)`. The README's MCP row flips from `designed · §7`
to `built`, which is the one-row edit the #47 table was shaped to make possible.

### The three decisions, and which were measured

**Library.** Hand-rolled JSON-RPC on the MVC stack, zero new dependencies. Not chosen by
taste: the Spring AI 2.0.0 starter was added to this build and `runtimeClasspath` resolved,
which is what [[concepts/bom-version-shadowing]] cost us the last time we trusted release
notes. It resolves cleanly and pins `spring-boot-starter-web:4.1.0` — our exact version, so
Boot 4.1 compatibility is real. What killed it is one measurement:
`io.modelcontextprotocol.sdk:mcp-core:2.0.0` declares protocol versions up to
`2025-11-25` and no further, so the SDK does **not** implement the current revision. "Let
the library own the protocol" is false when the library is a revision behind — we would
write the modern era anyway, on top of ~30 artifacts including Reactor and Jackson 3.

**Protocol revision.** Read from the spec, not from memory, and it had moved:
**`2026-07-28`** removed `initialize`, sessions, the GET stream and `ping`, made MCP
stateless, and added mandatory `server/discover`, per-request `_meta`, `resultType`, and
header/body agreement. The spec itself defines a **dual-era** server, and its compatibility
matrix settles it: modern-only fails every client shipping today, legacy-only is a revision
behind on arrival. So both — `2026-07-28`, plus `2025-11-25`/`2025-06-18` by handshake.

**Authorization.** The same token as `/watch`, deliberately: one process, one credential,
and this endpoint answers with the whole solving history so the bar cannot be lower. Plus
`Origin` validation, which MCP makes a MUST — the allowlist is empty, so any request
carrying an `Origin` is refused.

### Honest note on how this was built

**The production code was written before the tests on this branch.** That is a TDD
violation and it is recorded as one rather than described as anything else. All 12
production files have a paired test file now, failure paths included, but the ordering was
wrong and saying otherwise would be the exact defect — an artifact claiming a property the work
does not have — that #44, #47 and #48 were about.

Writing them afterwards still found three real bugs, which is the argument for writing them
first: the keyless `stats` bucket outranked real verdicts when its count was highest (a
model reading entry one would be told the most common verdict is "nothing"); records
sharing a timestamp came back oldest-first inside a list documented as newest-first; and
`.jsonPrimitive` on a member of the wrong JSON type threw, turning a malformed request into
a 500.

### Finding against #47

`README.md`'s architecture-diagram caption pointed at the *What works today* table and then
restated its answer in prose — "the sensor, the diffs and the MCP exposure do not". So
flipping MCP was two edits, not one, in the document whose ADR
[[decisions/2026-08-06-one-place-carries-tense]] exists to prevent exactly that. The
enumeration after the semicolon is dropped; the pointer stays. The rule caught its own
first violation within an hour of being written, which is the best evidence it earns its
keep.

### Remaining

- **A log line the store cannot parse is invisible to the client.** It is dropped with a
  server-side warning, and a count assembled above `RecordStore.read()` would under-report —
  a number that reads like a measurement but is not one. Closing it needs a port change.
- **No pagination.** `submissions` with no arguments returns the whole log.
- **Never spoken to a real MCP client.** Verified against the specification and over the
  real endpoint by contract test; not against Claude Desktop or Cursor.
- `WatchToken` and `tracker.watch.token` are now narrower than their role. Naming debt,
  recorded rather than silently fixed in an unrelated PR.

## [2026-08-06] The image could not do SSH, though compose.yaml documented it ✅

Issue #56, branch `fix/56-openssh-client`. Found while setting up the owner's real record
repository, by running the container against a real SSH remote rather than by reading files.

`compose.yaml` tells the user to uncomment an `~/.ssh` mount "to push over SSH". The runtime
stage installed `git curl` and nothing else, so `ssh` did not exist and the push died on
`ssh: not found`. Everything upstream of that looked correct — `docker compose config`
validates, the mounts resolve, the remote is right — which is why it survived: the failure is
only observable at the moment a push is attempted.

It also silently steered users toward the weaker credential. A GitHub **deploy key is
SSH-only** and is scoped to one repository; the documented HTTPS alternative uses a token
scoped to the whole account unless the user knows to make a fine-grained one.

Fix: `openssh-client` in the runtime stage (+4 MB, 483 → 487 MB). CI now asserts that every
binary the docs promise — `git`, `curl`, `ssh` — is present, so this cannot regress
invisibly. Verified by a real `git push` from inside the built container to a real GitHub
remote, authenticating as `BrokenFinger98/ps-records`, which is the deploy key rather than
an account key.

`ssh-keyscan` is also absent and deliberately left so: `docs/bootstrap.md` tells the user to
run it on the **host**, where it exists.

## [2026-08-05] Stage 3 wired — fetched code becomes files ⏳

Issue #36, branch `feat/36-attach-code-artifacts`. 549 tests, all five gates exit 0,
calculator coverage still 100%.

`CodeFetch` (#20) and the artifact generators (#34) are now connected. A settled grading
runs through `RecordWriter` (stage 2) and then `CodeAttachment` (stage 3), which fetches the
saved code and writes `Solution.<ext>`, `attempts/NNN.<ext>`, the diff and the README.

- **A fetch failure never touches the record.** `Unauthenticated`, `RateLimited`,
  `Unavailable` and a fetcher that *throws* all leave the stored line byte-identical with
  `codePending` still true and not one file written — four failure-path tests, one per branch
- **`codePending` is cleared by appending a corrected record**, not by editing the line.
  `RecordHistory` resolves the newest line per capture key, in the first line's position.
  ADR: [[decisions/2026-08-05-code-pending-correction-append]]. Both indexes the writer
  restores at startup are pinned by tests — the attempt counter (highest wins, a repeat
  cannot raise it) and the dedup index (a set the repeat collapses into, so a raw-log replay
  is still dropped)
- **Stage 3 shares stage 2's dispatcher**, wired as one `writerDispatcher` bean and proven by
  `CodeAttachmentSerializationTest`: 16 concurrent write-then-attach chains, peak concurrency
  inside the derived-write section is 1. The *fetch* is deliberately outside it — a page round
  trip held in the single writer thread would stall every other grading behind the network
- **Startup retries what is still pending**, in one runner after the raw-session
  reconciliation so records that pass recovers get their code in the same boot. Repeat-safe:
  an attached record is no longer pending, and the pass stops at the first `BLOCKED` outcome
  rather than asking an expired session the same question once per record

### Not done, and not claimed

- **No auth-state holder exists**, so `Unauthenticated` is logged at ERROR under an `AUTH`
  marker and blocks the pass. It does not feed a shared state the subscription path can read,
  because there is nothing to feed yet — inventing one was out of scope
- **Not verified against Programmers.** Every test doubles the fetcher; `liveCodeFetch` still
  needs a cookie and a browser. "Implemented" is not "measured"
- Attaching a `run` may fetch code from a later edit — the autosave race the pipeline ADR
  already accepts as honest rather than fixable

## [2026-08-06] Stage 3 wired — but held for the owner ⏳

Issue #36, branch `feat/36-attach-code-artifacts`, PR opened and **deliberately not merged**.
513 tests, all gates 0, calculator coverage 100%.

A settled grading now produces `Solution.<ext>`, `attempts/NNN.<ext>`, the diff and the
README. Fetch failures are tested three ways and none of them touches the record: the verdict
is unrecoverable while the code is re-fetchable, which is the whole reason this is stage 3.
Serialization proven with 16 concurrent write-then-attach chains showing peak concurrency 1
inside the derived-write section, plus a test that the fetch itself is not on that thread.

**Why it is not merged**: clearing `codePending` required deciding how a durable record is
corrected, and the chosen answer — append a corrected record, newest-per-`captureKey` wins —
changes what `log/submissions.jsonl` means. Design §5.1 calls it "every submission, one line
each", and every consumer that reads the JSONL directly must now resolve newest-per-key or
silently double-count. That is a change to the data contract, not an implementation detail,
so it is written up as a **proposed** ADR
([[decisions/2026-08-06-record-corrections-by-append]]) and left for the owner.

Also worth noting: the worker reported 549 tests where the tree has 513. Nothing is missing —
`verifyEveryTestClassRan` passes and all 53 classes produced results; the worker miscounted
the pre-existing tests in a class it edited. The guard is what made that answerable in
seconds instead of being taken on trust.

## [2026-08-06] Stage 3 wired — the record finally carries the code ✅

Issue #36, branch `feat/36-stage3-code-attachment`. 753 tests, all gates 0. Rebased from the
held PR #38 after the owner accepted the correction semantics.

A grading now produces its files as well as its record: the code is fetched after the record
is durable, `codePending` clears, `Solution.<ext>` and `attempts/NNN.<ext>` are written,
`diffFromPrev` is computed and the problem's `README.md` is regenerated. The fetch stays
outside the confined writer, so a page round trip cannot stall another grading, and a failure
leaves the record intact and pending for the next pass.

### What the owner decided, and why it needed deciding

Corrections are appended, not edited: a second complete line with the same `captureKey`,
newest-per-key wins. The log stays append-only — the property that lets it be the attempt
authority — but **`log/submissions.jsonl` is no longer one line per submission**, and design
§5.1 said it was. That is a documented data contract, which is why it was held rather than
merged unattended. §5.1 and §5.2 now state the rule.

Two ADRs described this decision — one written during the design phase, one written overnight
to escalate it. Neither had reached `main`, so they were merged into the earlier, more
complete one rather than left as a contradiction the wiki schema forbids.

### Two defects the merge itself surfaced

**The MCP read slice did not resolve corrections.** `RecordQuery` decoded the log's lines
directly, because #46 was built while this branch sat unmerged. Every attached submission
would have been listed twice by `submissions` and counted twice by `stats` — a pass rate that
looks plausible and is wrong. It now reads through `RecordHistory`, so there is one
implementation of the rule instead of two. Four new tests in `RecordQueryTest` cover it, and
they were **verified to fail** against the old reader before the fix was restored.

**The record object mother handed every record the same capture key.** Harmless while nothing
deduplicated; the moment a reader resolved newest-per-key it collapsed a whole log into one
record, and six reader tests that had been passing for the wrong reason turned red.
`aSubmissionRecord()` now issues a fresh key per call, which is what a real repository does.
This is the same shape as every other finding this week — a fixture that was not wrong yet.

### Merge conflicts and how they were resolved

`CaptureConfiguration.kt` — the branch predates #41 and #44, so it carried a startup runner
of its own while `main` had `StartupReconciliation`. Resolved toward one place: code
attachment became a fourth step there, ordered **after sessions and before git**, because a
recovered session is written pending and code attached after `git.reconcile` would sit
uncommitted until something else swept it up.

`progress.md` — append versus append; merged chronologically.

## [2026-08-06] The other problem shape, measured ✅

Issue #61, branch `docs/61-main-style-run-measurement`.

Captured live on lesson 181951 by hooking `App.cable.connection.webSocket` in the browser,
while investigating how to build the IntelliJ runner (#37). Programmers has **two problem
shapes** and we had only ever captured one.

- `solution(...)` problems — Programmers wraps them in its own `SolutionTest` harness, and a
  failing run reports through `run/error` with `msg` only.
- `main` + stdin problems — the per-case frame is **`run/testcase`**, carrying `stdout`,
  `stderr`, `exitCode` and `wallTime`. This answers the §14 question about whether stdout is
  retrievable: it is.

Three traps came with it, all now written down before a parser relies on them:

1. Example values are **JSON literals** — `3, 2` wraps to an argument array, `"4 5"` is the
   stdin text — but they are not strictly JSON. The expected output holds a **raw newline
   inside a quoted string**, which `JSON.parse` rejects. Miss it and only multi-line stdin
   problems break, which will look problem-specific rather than systematic.
2. **Newlines are encoded two ways in one response**: `\n` in the expected output, `<br/>`
   in `stdout`.
3. The problem statement renders differently per shape, and the **argument names exist only
   in the solution-style table** — the socket sends a flat `"3, 2"` with neither names nor
   arity.

Also measured, and previously listed as unknown: the list API **throttles, and fails as a
200 carrying an HTML error page** rather than a 429. Two of seven sequential requests at
2-second spacing came back as `서비스 접속 오류`; none did at ~5 seconds with retry. A client
that does not validate the body will store an error page as data.

Corrected while here: six documents claimed the solved.ac vocabulary has **180 tags**. It
has **229** (fetched 2026-08-06). The decision to adopt their vocabulary is unchanged; only
the count was stale.

## [2026-08-06] The problem catalog — built once, shipped ✅

Issue #63, branch `feat/63-problem-catalog`. 764 tests, all gates 0.
ADR [[decisions/2026-08-06-shipped-problem-catalog]].

689 problems with title, level, `partTitle`, acceptance rate and tags, loaded from the jar
into memory at startup. A user runs nothing and fetches nothing.

**536 classified by reading their statements; 153 taken from a `partTitle` that already
names the technique** (`SELECT`, `해시`, `깊이/너비 우선 탐색(DFS/BFS)` …). Tags come only
from solved.ac's published vocabulary, which now ships too — development-rules §8 required a
local replica and `.ps/` is gitignored, so no clone had ever had one.

### The arithmetic that decided it

The design specified a **daily** 689-problem refresh. The owner asked for it to be built now
rather than deferred, which left only the question of who collects it:

| | requests to Programmers |
|---|---|
| daily refresh, per user | ~250,000 / year |
| one local scan, per user | 536 × every user |
| **one scan, shipped** | **543, once, ever** |

The option that looks more considerate — every user scans their own — generates two orders
of magnitude more traffic to produce identical files. Shipping is the lighter choice, and the
counter-cost is that we distribute an index of ids and titles. No statements, no examples,
no test data: the statements were read to produce labels and discarded.

### Collection, measured

Metadata 7 requests, every response validated. Statements 536 requests at 1.6-second
spacing, **zero failures**. Labelling **zero requests** — eight Sonnet workers read files
already on disk, which is also why the fetching stayed serial and rate-limited instead of
being multiplied by the worker count.

Validated before shipping: 536/536 classified, no id missing or duplicated, no tag outside
the 229-tag vocabulary. Confidence high 434 / medium 81 / low 21, stored per entry so a
consumer can weigh it.

`ClasspathProblemCatalogTest` asserts against the **real shipped resource**, not a fixture.
The file is the artifact — it was assembled by a process nobody will run again, so a fixture
would test the loader and say nothing about what actually ships.

### The doc-path guard earned itself again

It failed the build because the ADR was not yet staged — existence is decided against git's
index rather than the working tree, exactly so a dirty workspace cannot make it pass. Second
time this week that guard caught something real.

## [2026-08-06] LICENSE carries the legal name ✅

Issue #67, branch `chore/67-license-legal-name`. One line.

`Copyright (c) 2026 BrokenFinger98` → `Copyright (c) 2026 YU SUNWOO`. The handle was a
placeholder from #48, added when the file was created because three documents cited a licence
that did not exist and no name was available.

Everything else is untouched. The text is byte-identical to canonical MIT, which is why
GitHub detects it — an edit anywhere else would risk that.

Worth recording so nobody later assumes this bought more than it did: **a name in a LICENSE
is an assertion, not authentication.** Neither a legal name nor a handle proves authorship;
commit history, account control and timestamps do. The narrow gain is that a legal name
matches any formal context directly, without the intervening step of showing that a handle
belongs to a person.

## [2026-08-06] Records carry what the catalog knows ✅

Issue #59, branch `fix/59-catalog-fields-in-records`. 768 tests, all gates 0.

Every record was written with `title = ""`, hardcoded at both capture sites with a comment
saying the catalog title "arrives on its own schedule". #65 shipped the catalog, so it does.
A record now carries the title, level, part, acceptance rate and tags, and a problem
directory is `problems/<lessonId>-<slug>` as design §5.1 always specified rather than a bare
number nobody can read.

Two things fell out of it that were not the point but are worth having:

- **Commit subjects gained their level.** `CommitMessage` was written to render
  `[Lv2] <title> — WRONG (12/16, attempt 3)` and had been silently dropping the bracket
  because level was always null. Its doc comment said "No catalog is wired yet"; that is no
  longer true and it now says so.
- **Records carry tags**, which is the axis weakness analysis needs. Nothing consumes them
  yet, but the data starts accumulating from this commit rather than from whenever §6 is
  built — and a grading cannot be re-fetched later to backfill.

### A dependency violation I introduced in #65, fixed here

`application/ProblemCatalog` returned `adapter.catalog.CatalogEntry` — a port in
`application` handing back a type owned by `adapter`, which dev rules §1 forbids in that
direction. `CatalogEntry` moved to `application` beside the port. It also matters
practically: a second source for the same facts, a locally fetched page say, would otherwise
have had to depend on the classpath reader in order to describe a problem.

### The absent case is the one that had to keep working

The catalog is a snapshot, so a problem published after it was built is unknown. That records
the grading anyway and leaves the catalogued fields empty — the grading cannot be fetched
again and the title can. Two tests pin it, one that the record still appears and one that the
fields stay absent rather than being filled with a stand-in.

Verified the new tests fail without the fix before keeping them: reverting `toRecord` to the
hardcoded empty title turned two of them red.

## [2026-08-06] A running instance says which build it is ✅

Issue #60, branch `fix/60-image-identity`. 768 tests, all gates 0.

`docker compose up` reuses a tagged image rather than rebuilding, so a user who pulls new
code and starts the stack keeps running the old one. It happened during the 2026-08-06
verification and invalidated a whole Docker pass: the image predated stage 3 by an hour, and
**the only symptom was a log line that never appeared**. An absent line is close to
invisible; it was noticed by accident.

That matters more here than in most tools, because this one records data that cannot be
re-fetched. A stale image writes records with whatever verdict rules it was built from and
says nothing about it.

Now the first lines of the log say:

```
Running build 0.0.1-SNAPSHOT — compiled 2026-08-06 14:37:39 UTC from commit 43578df.
If that predates your last pull, you are on a stale image: rebuild with `docker compose build`.
```

### The commit could not be read the obvious way

`.dockerignore` excludes `.git/` **for a security reason** — it carries every credential ever
committed and then removed — so a container build cannot read the commit for itself. Relaxing
that to get a nicer log line would have been a bad trade.

So the **build time** carries the feature: Gradle stamps it always, and comparing it against
when you last pulled is the whole diagnostic. The commit arrives as an optional build
argument (`SOURCE_COMMIT=$(git rev-parse --short HEAD) docker compose build`) and reads
`unknown` otherwise, which is honest rather than absent.

### The stamp is optional, deliberately

`BuildProperties` exists only when `build-info.properties` was generated, which a plain
`gradlew test` does not do. Requiring it would turn a missing build stamp into a context that
refuses to start — trading a diagnostic for an outage. An unstamped run says so instead, and
CI fails if the *image* is ever unstamped, because from outside that looks identical to a
stamped one.

Verified by running it: built with `SOURCE_COMMIT`, started the real container, read the line
out of `docker logs`.

## [2026-08-06] Minimised the Programmers content we hold ✅

Issue #62, branch `chore/62-minimise-third-party-content`. 768 tests, all gates 0.

Programmers' footer refuses 무단 복제 · 배포 of site content. Most of this project is
untouched by that — it observes broadcasts about the user's own submissions with the user's
own session, and fetches the user's own code. Two places held their data for no engineering
reason, and the owner decided to remove both.

### The design planned to copy problem statements. That plan is deleted.

Four places assumed the statements would be stored: the §5.1 tree (`README.md` = "problem
statement + examples"), the §5.3 tagging premise ("the server already stores the problem
statements"), the §6.11 vector-DB reasoning ("all original text … is preserved"), and the §7
`get_problem` description.

It was never implemented — the generated `README.md` carries ids, counts and attempt history
and no problem text. **The plan is what got removed, before someone built it.** A record links
to the problem instead; what a reader wants from a record is their own code and how it was
judged, and the statement is one click away and not ours.

### Fixture example values are now ours; protocol strings are still theirs

`testcases[].input`/`.output` in the run captures were Programmers' example values sitting in
a public repository. Substituted — **shape preserved exactly**, because the shape is the
measurement: comma-joined arguments, scalar expected value, one entry per example.

The Korean result strings (`실패 (시간 초과)`, `테스트를 통과하였습니다.`) stay verbatim.
They are **functional protocol values** that verdict classification matches on (protocol §7);
substituting one would make the fixture test a protocol that does not exist — exactly the
failure fixtures exist to prevent.

`fixtures/README.md` and dev-rules §7.3 now record which is which, so a later tidy-up does not
"fix" a substituted value back.

### One test was asserting borrowed data

`SubmitMessageFailureTest.parses example testcases from run start` compared the example values
literally, so substituting them turned it red — correctly. Rewritten to assert the **shape**:
two entries, a comma-joined argument string, a non-blank expected value. That is what the
parser actually has to get right, and it no longer depends on holding somebody else's numbers.

## [2026-08-06] One spelling of the silence rule ✅

Issue #49, branch `refactor/49-liveness-one-spelling`. 762 tests, all gates 0.

`ConnectionLiveness` presented itself as the liveness policy — a `Liveness` sealed interface,
`frameArrived()`, `check()`, `isDead()`, an `AtomicReference` for the last frame, and a class
comment saying detection was "the whole point of this class". **None of it ran.** The one
production consumer, `CableChannelSubscriber`, expresses the same rule as a Flow
`timeout(silenceDeadline)` and takes only the constants.

Checked for a planned consumer before deleting: the design describes liveness as a detection
mechanism, which the Flow timeout implements, and no health endpoint is planned. So option 1
of the issue — delete the unused instance API — was the honest one.

It is now an `object` carrying the numbers and the reasoning that fixes them. Call sites are
unchanged: `ConnectionLiveness.DEFAULT_DEADLINE` and `retryDelayFor` read identically.

The reason this was worth doing rather than leaving: **the spelling that does not run is the
one that misleads.** Someone adding a second observation path — a replay, a health endpoint —
would wire it to `isDead()` believing they had adopted the shipped policy, and would get a
second implementation of a rule the pure calculators exist to keep singular (dev rules §3).

The tests came with it. They now assert **bounds rather than literals**: each number sits
between two failures, so they compare against the measurements that constrain it — the 3 s
ping cadence and the 120 s grading timeout — instead of restating the constant. A test that
only pins the literal lets someone change it to another number that still passes and still
loses graded results.

## [2026-08-07] A heartbeat behind the lock ✅

Issue #52, branch `fix/52-heartbeat-on-lockless-filesystems`. 773 tests, all gates 0.
ADR [[decisions/2026-08-07-heartbeat-behind-the-lock]].

`RecordRepositoryLock` was built for one scenario — `docker compose up` while `bootRun` is
alive — and measuring it found that **on a Docker Desktop bind mount it protects nothing**.
`tryLock` returns a lock that excludes nobody and raises nothing, so neither the refusal nor
the escape hatch fires. The scenario it existed for was the one it did not cover.

A liveness marker now runs behind it, needing only `write` and `stat`.

### Change, not age — and that is the whole design

The obvious shape is a marker aged by mtime, and it is wrong in the one case that matters: a
container and its host can disagree about what time it is, and an age computed against the
wrong clock either refuses a free repository or admits a second writer, silently.

So a holder rewrites the marker every beat and a starter reads it, waits, reads again.
**Changed means alive.** Equality of two reads needs no agreement about when.

### A test caught a real hole in the token

The token started as `pid-counter`, which meant two instances *inside one process* wrote
byte-identical markers — a takeover would have made a live holder look stale. Now
`pid-nanoTime-counter`, and each part earns its place.

### Verified where it matters

Two containers on the same Docker Desktop bind mount — the exact configuration `compose.yaml`
produces, and the one the kernel lock could not cover. The second refused. The refusal names
the mechanism, because a kernel lock is gone the instant its holder dies while a heartbeat has
to be observed to stop changing, and telling a user the wrong one sends them to wait for
something that will not happen.

### What it does not do

Strictly weaker than a lock: two instances started inside the same watch window can both see
no change and both proceed. The kernel lock closes that wherever locking works; this closes
the common case where it does not. Windows and network filesystems remain expected-but-unmeasured.

## [2026-08-07] A cached-result UNKNOWN says why ✅

Issue #74, branch `fix/74-cached-result-reason`. 786 tests, all gates 0, calculator
coverage held.

Submit the same code twice and the browser renders the cached scoreboard — 100.0 — while the
record says UNKNOWN. Both are correct and they disagree; during the 2026-08-07 verification
that contradiction cost real investigation time, and the only explanation lived in a JSONL
field nobody opens. For a user it is indistinguishable from the tool being broken.

**UNKNOWN stays.** The socket delivered no verdict, and inventing PASS because a message
mentions a previous grading would fabricate a record from a hint. What changed is that the
*reason* now reaches every place a human meets the record.

### One classifier, three consumers

`domain/calc/UnknownReason` — a pure calculator matching **measured strings exactly**. The
cached-result message (`같은 코드로 채점한 결과가 있습니다.`, measured on 181951 and 181952)
classifies as `CACHED_RESULT`; anything else stays unexplained rather than guessed, because a
wrong reason printed confidently is worse than none.

- commit subject: `[Lv0] 문자열 출력하기 — UNKNOWN (cached result, attempt 1)`
- README attempt row: `UNKNOWN (cached result)`
- MCP: an `unknownReason` field on the summary — which matters doubly, because the summary
  view drops `errorText` for weight, so the one record a user asks an AI about was exactly
  the one whose explanation had been trimmed

### Drift is loud now

The measured string is the only cached-result marker there is. If Programmers rewords it,
classification degrades to plain UNKNOWN — correct — but now with a WARN naming the
unrecognised text, so the drift is noticed rather than rediscovered by reading raw frames.
The warning reads the same `boundErrorText ?: errorText` the verdict resolution reads, so the
two can never disagree about what they saw.

### The capture became a fixture

`fixtures/algorithm-cached-result.jsonl` — our own live frames from 181952: `start` then a
terminal `error`, no verdict frames at all. Registered in the fixture README with provenance.
The lesson from `assumption-vs-measurement` applied in the same change the capture happened,
rather than months later.

## [2026-08-07] Run examples reach the record repository ✅

Issue #37 step 1, branch `feat/37-carry-run-examples`. 798 tests, all gates 0.

The `run` `start` frame ships the judge's example pairs inline (protocol §7) and they
stopped at the protocol boundary — `GradingMessageMapper` kept only the count. They now
travel the whole pipeline: `ProblemExample` (domain, wire names stop at the mapper per the
dependency direction) → `GradingFrameFacts.announcedExamples` → `GradingSession.examples` →
written by the confined writer as `problems/<id>-<slug>/examples.json`.

Decisions that will matter to step 2:

- **Stored as strings, exactly as measured.** The values are JSON-*like*, not JSON — a
  main-style expected output carries a raw newline inside its quotes (§7.1). Parsing them
  into types at capture would bake one interpretation into every record; the generator
  parses at generation time against the user's own signature, and refuses what it cannot.
- **Replace-only, and an empty announcement is a no-op.** A submit announces no examples and
  must not blank what the preceding run wrote; two tests pin the pair.
- **Inside the confined section**, after the append — a derived write to the problem
  directory must not interleave with another grading's (write-serialization decision 1).
- **Best-effort**, like the raw move: the file regenerates on the next run, so losing a
  write never costs the record it rode in with.

The end-to-end tests are driven by the measured run capture; its example values are our #62
substitutions, whose shape is the measurement.

## [2026-08-07] The server generates runners — Java first ✅

Issue #37 step 2, branch `feat/37-carry-run-examples` (continues step 1). 834 tests, all
gates 0. ADR [[decisions/2026-08-07-server-generated-runners]].

`(user's code, measured examples) → RunnerTest.java`, or a refusal that says why. Wired into
the attachment, so the runner rides the same trigger as the code it tests, and a refusal
deletes a stale runner rather than leaving one that tests yesterday's solution.

The owner settled both open questions: the server generates (an AI-over-MCP design was
declined — it must work out of the box), and the support order follows measured usage —
java → python3 → cpp → javascript → kotlin → c → csharp, from the pagination depth of one
problem's shared solutions, bias stated.

The line held throughout: **a runner that compiles and tests the wrong thing is worse than
none.** Types come only from the user's own signature; the signature parser is shallow and
refuses generics; example values parse in exactly one place that handles the §7.1
raw-newline trap; every mismatch refuses with an actionable reason.

**Java earned "supported" by execution, not by review**: the suite compiles and runs
generated runners in child JVMs — a correct solution passes, a wrong one fails naming the
example, both shapes, including the measured 181951 stdin case round-tripped through
generation, `System.setIn`, and stdout comparison.

## [2026-08-07] Python runner — second language, earned by execution ✅

Issue #78, branch `feat/78-python-runner`. 857 tests, all gates 0.

`PythonSignature` (names and arity — Python declares no types, so this generator has one
check fewer than Java's and does not pretend otherwise), `PythonRunner` (both shapes), and
per-language dispatch in `FileDerivedArtifacts` with a stale-runner sweep across every
runner file name.

Shape detection needed a per-language split: Python has no `main`, so a main-style script is
top-level code reading `input()` — and the priority is **reversed** from Java's, `def
solution(` winning, because the solution-style skeleton always declares it while the
main-style one never does. The main-style runner re-runs `Solution.py` as a fresh child per
example, since a script executes at import time and could only ever run once in-process.

Two things the execution tests caught before CI could:

- A deliberately wrong main-style fixture with **no `input()` at all** has no shape signal
  and is correctly refused — the test now reads input like a real wrong answer would, and
  the no-signal case is covered as the refusal it is.
- The solution module is `Solution.py` (capital S, from `CodeArtifacts`), so `import
  solution` would have broken on case-sensitive filesystems. Checked against the actual
  writer rather than assumed.

CI now asserts the Python execution suite **genuinely ran** on every runner, from the
results file: the suite skips politely where python3 is missing — right for a contributor's
machine, wrong in CI, where a skip would silently un-earn the supported status.

## [2026-08-07] C++ runner — third language, the single-translation-unit one ✅

Issue #80, branch `feat/80-cpp-runner`. 894 tests, all gates 0.

Measured first, from the actual editor (Orca browser, 2026-08-07): 181951 ships `int
main(void)`; 120803/120817/12950 declare by-value parameters under `using namespace std;`,
nested vectors spelled `vector<vector<int>>`. Four skeletons, both shapes, cited in the
shape/signature tests.

C++ is the first language whose harness shares one translation unit with the user's code
(`#include "Solution.cpp"`), which forced three moves the earlier runners never needed:
file-scope harness identifiers wear a `runner_` prefix (a user's name collision fails
loudly at compile time, never silently tests the wrong thing); solution arguments are
**named locals** so hand-edited reference signatures bind; main-style renames the user's
`main` via the preprocessor and swaps `cin`/`cout` buffers in-process — with `cin.clear()`
between examples, because the previous run leaves eof bits behind.

One honest divergence: a missing expected value **refuses** here — Java compares against a
`null` placeholder and Python against `None`, but C++ has no untyped placeholder that
compiles.

Execution suite: 7 tests under a real compiler (`g++`/`clang++` probe), including nested
vectors and strings specifically because each instantiates a different `runner_str`
overload set — templates only prove themselves at instantiation. The CI proof-ran step now
loops over all three suites, and gained a job: a results file gone missing (suite renamed
or dropped) fails the same gate.

## [2026-08-07] JavaScript runner — fourth language, the no-type-mapping one ✅

Issue #82, branch `feat/82-javascript-runner`. 917 tests, all gates 0.

The luxury language: §7.1 example values are JSON and JSON is valid JavaScript, so
literals embed verbatim — the one runner with no type mapping to get wrong. What JS takes
back is loading: the measured skeleton declares `function solution(...)` and exports
nothing, so `require()` cannot see it. The runner loads `Solution.js` as a script via
`vm.runInThisContext` (the purpose-built API — the security hook rightly flagged the first
eval-based sketch), where a function declaration lands on the global object. That loading
choice is also why `JavascriptSignature` admits only the declaration form: a
`const solution = ...` arrow stays script-scoped, and a runner calling it would throw.

Main-style (181951: top-level readline over process.stdin) re-runs as a fresh `node` child
per example — Python's subprocess rationale. Deep comparison via `JSON.stringify`, which is
order-sensitive over arrays like the judge. CI proof-ran loop now covers four suites.

## [2026-08-07] Kotlin runner — fifth language, the two-main-traps one ✅

Issue #84, branch `feat/84-kotlin-runner`. 947 tests, all gates 0.

Measured (editor captures 2026-08-07): 120803 `fun solution(num1: Int, num2: Int): Int`
inside `class Solution`; 120817 takes the **primitive** `IntArray`; 12950
`Array<IntArray>`; 181951 a top-level `fun main(args: Array<String>)`.

Both shapes compile in one unit with the user's file, and Kotlin's two traps are both about
`main`: the harness entry is an `object RunnerTest { @JvmStatic fun main }` (a top-level
one would collide with the user's), and the user's `main` is reached through a **top-level
bridge function** — inside the object an unqualified `main(...)` resolves to the harness's
own entry, and the `SolutionKt` facade is invisible to Kotlin resolution. No reflection.

Equality is by content (`contentEquals`/`contentDeepEquals`) because Kotlin arrays compare
by reference under `==` — the array execution cases exist to prove that dispatch for real.
Kotlin strings interpolate `$`, so the quoting table grew one entry.

The execution suite compiles generated source with `kotlin-compiler-embeddable` through
`CLICompiler.doMainNoExit` — kotlinc's own entry minus the exit — so it is hermetic on all
3 CI runners and can never skip. The **two-example main-style test settled the open
question**: `readLine()`'s LineReader follows a swapped `System.in`, so the in-process
harness stands, and that experiment is now pinned as a test.

## [2026-08-07] C runner — sixth language, where one wire value is not one argument ✅

Issue #86, branch `feat/86-c-runner`. 982 tests, all gates 0.

Measured (editor captures 2026-08-07): a 1-D array travels as `int name[], size_t
name_len` (120817/120821), a 2-D one as `int** name, size_t name_rows, size_t name_cols`
(120860), strings as `const char*` in and malloc'd `char*` out (120822), and a returned
`int*`'s length is implied by the answer. 12950 does not offer C at all — older problems
carry narrow language lists, which the capture guard caught when the dropdown stayed on
Java.

`CSignature` therefore parses **physical** parameters and groups them into **logical**
ones — the wire arity counts the logical side — refusing any grouping the skeletons never
showed: unpaired `size_t`, mis-named lengths, string arrays, non-int array elements, grid
returns. The generator stages a 2-D value as row arrays behind a pointer array (an
`int[2][2]` does not convert to `int**`), and a missing expected value refuses, C having
no untyped placeholder.

Main-style reuses C++'s rename-and-include, but C has no stream objects to swap: each
example stages stdin in a temp file and `freopen`s both standard streams, so the harness
reports on **stderr** — stdout belongs to the solution under test. Skipping the dup2
restore dance keeps it portable; windows-latest is MinGW's `freopen` question, the way
`\r\n` was Java's.

The execution suite caught a fixture lie before CI could: minimal test solutions omitted
the skeleton's own includes and `size_t` vanished. Fixtures now carry the measured
includes, and the harness guarantees `<stddef.h>` ahead of `Solution.c` — the parsed
convention itself speaks `size_t`, so its visibility is the runner's premise, not the
user's chore.

## [2026-08-07] C# runner — seventh language; the series is complete ✅

Issue #88, branch `feat/88-csharp-runner`. 1012 tests, all gates 0.

Measured (title-guarded captures): `int[]` with no length params, the rectangular
`int[,]` (not `int[][]` — its comma forced a bracket-aware parameter split), `string`,
and a main-style skeleton whose class is **Example** with a `Console.Clear()` inside.

C# is the first **two-artifact** runner: C# compiles projects, not files, so
`runner_test.csproj` rides along as `Runner.ExtraFile` (a default-empty extension — zero
churn for the six existing runners). The csproj is where the traps are disarmed: explicit
`<Compile Include>` (default globbing would swallow every `.cs` under `attempts/`, each
declaring another `Solution`) and `<StartupObject>` pinning the entry (the skeleton's own
`Main` would otherwise be CS0017). The harness compares through `object` with runtime
dispatch — `SequenceEqual` for arrays, rank+`Cast<int>()` for `int[,]` — so Java's null
placeholder works.

Honest status: **the C# execution suite skipped locally** — this machine's dotnet is a
broken x86_64 host on arm64 — so CI's three runners are the proof-bearer, and the
proof-ran gate + merge-only-on-green keep that proof ahead of shipping. The main-style
fixture keeps `Console.Clear()` in on purpose: its behaviour under redirected output is
measured per-OS by the suite, in CI.

ADR outcome updated: series complete, 7/7, with the per-language facts pinned.

## [2026-08-07] #93 — GitSync no longer adopts an enclosing repository ✅

`detectRepository` asked `git rev-parse --git-dir`, which succeeds from *any* subdirectory
and answers about the enclosing repository. A records directory nested inside another
project therefore passed the check, and `reconcile`'s repo-wide `add --all` committed that
project's unrelated working tree under our message, ready for the next push. Reproduced
before fixing: `secret-wip.txt` and `src/App.java` staged from a neighbouring project.

The comment directly above the check already claimed to cover exactly this case. The fix
is the question it should have asked: `--show-toplevel`, compared against the configured
root as real paths so a symlinked or `/private`-prefixed root still matches.

The new test asserts on the thing that matters — the enclosing project's work is still
uncommitted afterwards.
## [2026-08-07] #91 — a debug main no longer hijacks the runner ✅

"`main` wins when both appear" was right for main-style problems and wrong for
solution-style ones, where the judge calls `solution(...)` and never touches `main`. A
scratch `main` the user left behind produced a stdin harness containing no `solution` call
at all — and it printed `ALL PASS` while the graded code was wrong. Reproduced under JDK 25
before fixing; the same flip was confirmed for kotlin, csharp, cpp and c.

The shape is genuinely undecidable from the code: a refactored main-style problem and a
solution-style one with a leftover `main` are identical here. So `ProblemShape` gained an
`AMBIGUOUS` state and the five affected generators refuse it with the remedy stated.

The distinction that keeps the original intent alive is **declaration versus call**: a
`main` program that merely *calls* a helper named `solution` is still main-style, and each
language's own signature parser is what answers "declares". Java gets this for free — the
parser requires `public`, which is what the judge's skeleton has.

Python and JavaScript resolve the same collision the other way, which is their measured
rule; the mirror-image risk there is unmeasured and left alone rather than guessed at.
## [2026-08-07] #92 — protocol values can no longer become code in a generated runner ✅

Strict parsing was treated as sufficient and is not: kotlinx-serialization accepts bare
unquoted tokens even in non-lenient mode, returning a primitive whose `content` is the raw
text. Only whitespace and `" [ ] { } : \` terminate such a token, so `( ) ; . ' |` ride
through, and a comma-free payload also clears the arity check.

`JavascriptRunner` emitted `it.toString()` and `PythonRunner` fell through to
`value.content`, so the token landed in the generated file verbatim. Reproduced before
fixing: `require('fs').mkdirSync(...)` actually ran under node, and the runner reported a
plain `FAIL` — the injection was invisible in its own output.

Fixed at `ExampleValues`, the one place §7.1 values are parsed, so all seven languages
agree. The five typed generators already refused these by coercing; this makes the two
text-emitting ones match rather than patching them separately.
## [2026-08-07] Adversarial review, and the first fix ✅

Four independent reviews (runner correctness, capture pipeline, security/privacy, product
gap) attacked the finished system. They found what the finished-series report did not:
**two paths that print `ALL PASS` on wrong code**, protocol values reaching generated
runners as executable code, an enclosing-repo mistake that commits the user's unrelated
work, and a liveness rule whose stated premise is inverted in the code. Ten issues filed
(#90–#100), each reproduced before filing rather than argued.

#90 first, because it is the charter violation: `JavaRunner`'s generated `check` ORed a
rendered-string comparison onto `deepEquals`. `deepEquals` already compares arrays deeply
and every boxed scalar, so that clause could only ever add passes — and
`Arrays.deepToString` is not injective, so a one-element `{"a, b"}` passed against a
two-element `["a", "b"]`. Reproduced under JDK 25 (`ALL PASS`, exit 0) before touching it.
Java was the oldest generator and the only one still carrying the leniency the later six
dropped.

The regression test runs the colliding input for real, because the old code passed it.
1015 tests.
