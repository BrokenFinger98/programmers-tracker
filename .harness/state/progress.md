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
([[decisions/2026-08-05-code-pending-correction-append]], proposed as
`2026-08-06-record-corrections-by-append`) and left for the owner.

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

## [2026-08-07] #95 — the recovery queue now outlives the record write ✅

`rawPathOf` was evaluated as an argument to `toRecord`, so the frames were physically moved
out of `.ps/raw` — the only directory the reconciler scans — *before* `store.append`. A
failed append (a full disk, an unmounted record repo) therefore took the grading out of the
recovery queue for good, while the log line said its frames were kept.

`complete` now **copies** and a new `discard` retires the source once the record is durable.
An interruption anywhere before the discard leaves the file where the reconciler replays
it, and the content-digest capture key drops the replay as the duplicate it is.

Two details the tests forced out. The copy result decides the recorded path, so a failed
copy still names the raw directory — where the frames actually are — instead of a tidier
path that would be a lie. And `discard` runs only when the copy succeeded, otherwise a
record pointing at `.ps/raw` would have its own file deleted underneath it.

## [2026-08-07] #94 (first half) — the ping now resets the silence deadline ✅

`ConnectionLiveness` documents the 3-second ping as "the one liveness signal we get for
free", but `SubscriptionProtocol` mapped it to `Ignore` and `RawSocket` turned that into
`Unit` — so the ping never left the flow, and `.timeout(silenceDeadline)` was measuring the
gap between **gradings**. Opening a problem and reading it therefore reconnected every
~15 seconds, indefinitely, against Programmers.

The ping is now a `CableEvent.Heartbeat`. The deadline sits **above** the filter that drops
it, so the timeout sees it and the capture never does. `Step.Ignore` had no producer left
and was deleted rather than kept as a state nothing reaches.

Why it survived: `LiveObserve` — the tool the idle-close was measured with — applies no
timeout at all, and the subscriber's tests stub `client.observe` with a hand-built flow,
so no test had ever put a ping through the real composition. Two now do: one at the
protocol layer, one driving heartbeats through the subscriber and asserting both that it
does not reconnect and that the capture is never handed one.

The fixture helper had to learn the same rule — it claims to produce "what a subscription
hands the capture", and production hands no heartbeats.

**Second half still open**: a grading whose `start` frame was missed is still discarded
before the raw log. Split into its own issue because where orphan frames should live is a
design question that interacts with raw-directory hygiene (#99).

## [2026-08-07] #96 — six documents stopped describing a system that no longer exists ✅

All six were verified against the code before being touched, and two of them were costing
users a working feature or a correct setup:

- `docs/bootstrap.md` said **"No MCP server"** — false since #46, and it sat in the section
  framed as the honest gap list, so readers believed it and never opened `mcp.md`.
- `.env.example` shipped `TRACKER_RECORD_REPO=/absolute/path/to/ps-records`, which
  **satisfies** compose's `${...:?}` guard. An unedited `.env` started cleanly and recorded
  into a directory named after the placeholder. The value is now empty, which the guard
  rejects — confirmed by running `docker compose config` against it.
- README listed the runner as java-only; seven languages ship.
- `mcp.md` and `McpToolCatalog` both gave "no catalog exists yet" as the reason
  `list_problems` is absent. It has shipped in the jar since #69, so the reason expired —
  now stated as merely unexposed (#100).
- `ProblemReadme`'s two comments claimed no catalog is wired and every record carries an
  empty title.

`template/ps-records/README.md` is the one the user reads first, and it was the worst: five
Dataview dashboard notes nothing generates, a `SolutionTest.java` the server does not write
(it writes `RunnerTest.java`), and a `meta.json` that has never existed. Rewritten around
what is actually produced, with the dashboards named as *not yet written* instead of
promised.

That file is also the one document `scripts/guards.sh` cannot check — every path in it is
relative to a repository living elsewhere, which the guard deliberately skips. So the
invariant is pinned as a test instead, and the test was proved by reintroducing both
fabrications and watching it fail.

## [2026-08-07] #97 — the sensor extension exists, and its limits are stated ✅

The gap that kept this tool to one person: without a sensor, every problem had to be
registered by hand through DevTools and `curl`, again after each language-tab switch and
each server restart, and a submission on an unregistered problem is lost silently.

Measured before writing, on a live page (lesson 120803): all five identifiers read exactly
as design §8 sketched, and a language-tab switch replaces the code input — `language` and
`codesKey` change, `challengeableId` does not. That last fact is what the change detection
keys on.

One thing the design predates: **a content script cannot make this request.** Its
cross-origin fetch is subject to the page's CORS rules, and the server publishes none —
correctly, since permissive CORS would let any page in the browser reach it. So the content
script reads the DOM and hands the body to a service worker, whose `host_permissions` cover
`127.0.0.1`. The badge is the entire UI, because a sensor that fails silently reproduces the
exact failure it exists to remove.

The request contract was exercised against a running server with the measured identifiers:
`started`, then `refreshed` on the repeat, then `401` with the error body the badge shows.

**Stated as unproven, not done**: nobody has loaded it in a browser end to end, so the
manifest wiring, the worker relay and the badge are written and unexercised. The
constitution's rule about external-interaction features applies, and the README, the
bootstrap gap list and the README status table all say so rather than implying otherwise.

## [2026-08-07] #100 — list_problems, the tool the catalog was already paying for ✅

The 689-problem catalog and the 229-tag vocabulary have shipped in the jar since #69 and
were loaded at startup, but the only consumer was title lookup during capture. Nothing
exposed them, and `McpToolCatalog`'s own comment still gave "no catalog exists yet" as the
reason the tool was absent.

`list_problems(level?, part?, tag?, status?)` joins the catalog against the records.
`status` is the point: **`untouched` is the answer no other tool can give**, because the
records alone cannot separate "never tried" from "tried and failed" — both are simply
absent from a verdict tally, and design §5.6 says those two call for different
prescriptions.

The join is a pure calculator (`CatalogBrowse`) over two in-memory snapshots per dev rules
§3, so a browse and any later re-analysis cannot drift. Filters all narrow, and one naming
something the catalog does not contain returns nothing rather than everything — an empty
answer to a precise question beats a full answer to a different one.

Verified against a running server, not only in tests: 689 total, 240 untouched at level 0,
100 in 코딩테스트 입문, 0 for a tag the catalog genuinely lacks, and both bad-argument paths
answering with the correction rather than a widened result.

## [2026-08-07] #98 — four narrower false-PASS paths, each reproduced first ✅

All four were generated and **run** before being touched; three printed `ALL PASS` with
exit 0 on code that was wrong or had crashed.

1. **The "expected value not captured" placeholder was compared against.** A half-captured
   example plus a stub returning null passed. C and C++ already refused this input; Java,
   Kotlin, C#, JavaScript and Python now do too, so all seven agree and the refusal names
   the reason.
2. **Java main-style substituted `""` for an unreadable expected.** The stdin side two lines
   above already refused; the expected side defaulted, so the harness asserted the program
   prints nothing — the constitution's forbidden substitution, next to the guard that does
   it right.
3. **Python and JavaScript main-style ignored the child's exit status.** A solution that
   printed both correct lines and then died reported `ALL PASS`. The judge sees `exitCode`
   on the run/testcase frame (protocol §7.1), so it would not have been fooled; now neither
   is the runner, and the failing line carries the exit code and the last stderr line.
4. **C compared a returned `int*` over the expected length only.** This one cannot be
   fixed — C has no way to ask a pointer for its length, and reading past a shorter block is
   undefined behaviour. So it is **stated** instead: the verdict line now reads
   `PASS (first 3 elements; C cannot report the returned length)`, and the generated file's
   header says the judge does see what this check cannot.

Every regression test **executes** the generated runner. Text assertions would not have
caught any of these, and did not.

## [2026-08-08] #99 — a run's frames are set aside, and rawPath tells the truth ✅

Traced from the symptom: four `action: run` sessions sitting in `.ps/raw` from two days of
use. `SettledCapture.movesRaw` is false for a run — correctly, design §5.1 says a run
creates no attempt file — so nothing copied the session and therefore nothing retired it.
The reconciler re-read every one on every boot, and the record's `rawPath` carried a bare
session id that resolves against the record repository and matches nothing there.

Two rules pointed opposite ways: design §5.1 says no attempt file for a run, the
constitution says never discard an original. Resolved by putting the frames where neither
rule reaches — `.ps/raw/recorded/`, the tool's own state directory, outside the record
repository in both supported layouts — and making `rawPath` nullable.

Null is the honest statement. A path that resolves to nothing reads exactly like a file
that was lost, which is the assumption-vs-measurement mistake this repository already made
once.

`unprocessed()` keeps only direct children whose name parses as a session, so a
sub-directory is enough to take one off the work list — no marker file, no second index.

Schema change, so design §5.2 is amended in the same branch and the reasoning is in ADR
`2026-08-08-run-raw-sessions`, including the accepted costs: `.ps/raw/recorded/` grows
unpruned, and a run's frames are no longer reachable *from* the record.

Three tests pinned the old behaviour and were rewritten. One of them was the cross-restart
dedup proof, which the retirement makes unreachable for runs — so it was split: one test
proves a recorded session leaves the work list, another puts the frames back, as a failed
retirement would, and proves the capture-key index still refuses the second record.

## [2026-08-08] #107 — a missed start now costs the verdict, not the evidence ✅

`outsideGrading` treated two very different frames alike. A frame carrying no grading facts
is protocol noise — welcome, the subscription confirmation, anything trailing a terminal
frame — and DEBUG is right for it. A frame that *does* carry facts is a grading frame whose
`start` was missed, and dropping that silently is exactly how a change in Programmers'
framing would stay invisible (dev rules §2.3).

They are now told apart by `frame.facts`, which is non-null precisely for a broadcast
carrying a `SubmitMessage`. An orphaned grading frame is kept under
`.ps/raw/orphans/<lessonId>.jsonl` and reported at WARN with its action and a running count
— never its text, which carries solving history.

It never joins the work list, and that is deliberate rather than incidental: without a
`start` there is no action and no identity, so replaying it could only fail forever. The
sub-directory trick from #99 does the work again.

The test that pinned the drop as intended behaviour (`a terminal frame with no grading in
flight is ignored`) is replaced by two: one proving a grading frame is kept, one proving
protocol noise still is not.

## [2026-08-10] #114 — /watch now asks for only the two things the server cannot know ✅

Came out of the owner asking a good question: the session cookie is fixed per user, so why
does anything else need supplying? Answer: the cookie says *who*, the channel identifier
says *which problem's broadcast* — and protocol §10 measured that there is no wildcard and
no per-user channel, so the server must be told which problem is open.

But it was being told far too much. Protocol §3 measured every identifier as extractable
from the problem page with **no login required**, and `challengeable_id`/`challengeable_type`
as fixed per lesson and language-independent. So the server can read them itself. `codesKey`
was worse: carried through the payload into the command and consumed by nothing at all.

`/watch` now takes `{lessonId, language}`. A `PageProblemIdentityResolver` reads the rest
off the page and caches by lesson forever — the sensor heartbeats every 30 seconds, and a
fetch on each of those would be one request per problem per half-minute against Programmers.
Only successes are cached; a failure is usually an expired cookie and remembering the "no"
would outlive the fix.

Two things improved that were not the point. A request that cannot be resolved now answers
**502 `PROBLEM_UNRESOLVED`** naming the likely cause, where the old path accepted anything
well-formed and said `started` — that is a piece of the known "/watch always answers
started" gap, closed as a side effect. And the manual route lost DevTools entirely: the
problem number is in the URL.

Measured before writing: the markup carrying the identifiers, on a live algorithm page and
a live SQL page. It also carries `data-user-id`, which the new fixtures scrub.

Verified against a running server, not only in tests: two-field start, heartbeat refresh,
language switch, a SQL problem, an unresolvable lesson answering 502 with the reason, and a
missing field answering 400.

## [2026-08-10] #116 — the sensor catches up to the two-field contract ✅

Small change, and the point of it is that the extension is now loadable.

`lessonId` comes from the URL rather than `[data-lesson-id]`, because that attribute is
absent on a problem's sub-pages — measured on `/lessons/<id>/questions` — and the URL is
where the number always is. `language` still comes from the code input's `data-language`,
the only field that genuinely needs the DOM. Everything else the extension used to read is
the server's job now.

Verified by running the rewritten reader against three live pages: a problem page gives the
two fields, a SQL problem gives them with `mysql`, and a questions page with no editor reads
as nothing rather than announcing a channel with no language.

Still unproven: nobody has loaded it in a browser. That is now the only thing standing
between this tool and being usable by someone other than its author.

## [2026-08-10] #118 — the sensor is proven, and the start command no longer hides the rebuild ✅

**The extension works.** Loaded unpacked in Chrome, token pasted, a problem page opened:
`watching lesson 181947 in java (refreshed)`, green. The server logged the same build
answering. Every document that said "nobody has loaded this in a browser" now says what is
true, with the evidence — that claim was right until today and wrong afterwards.

This closes the last thing standing between the tool and someone other than its author
using it. Steps 1–6 of the design's workflow now work end to end without DevTools.

The first attempt failed, and the failure was mine to own. The badge read
`400 INVALID_REQUEST — challengeableId is missing` — a field the current server does not
look at — because the container was running a four-day-old image. `docs/bootstrap.md` gave
the start command as plain `docker compose up -d` and explained the rebuild in a blockquote
*below* it, which is not where a copied command comes from. `README.md` already had it
right, so the two documents disagreed and the wrong one was the walkthrough. The command
now rebuilds by itself, and the note carries the measured symptom so the next person
recognises it.

Also corrected while there: the gap list still said the MCP server exposes three tools;
`list_problems` shipped in #110.

## [2026-08-10] #120 — the sensor records what the grading stream cannot ✅

Design §6.4 wants `confidence = f(attempts, hint level, elapsed, performance)` and two of
those four did not work: `hintLevel` is a dead field with no writer, and `elapsedSec` is
wall-clock, so a problem opened before dinner reads as three hours.

Both fixes are only observable in the browser, only while solving, and impossible to
backfill — which is what makes collecting them now right rather than speculative. Built
today so tonight's problem is the first one recorded with them.

**Measuring first kept a second dead field out.** An earlier sketch had "did you look at
다른 사람의 풀이". Measured: that tab answers 401 until you have already solved the problem
(120803 unsolved → 401; 120804, 181951 solved → open), so it could only ever have recorded
false. The 질문하기 tab does open on unsolved problems and its first post is 문제 풀이
공유합니다 — that is the real signal, and it is what shipped. Time-to-first-keystroke was
dropped for having no consumer.

The timers document changed shape to hold the readings, and **reads both forms**: the
developer's live file had five clocks running in the old flat shape, and dropping them would
have put a wrong `elapsedSec` on the next record. A wrong number is worse than an absent one.

Absent stays distinguishable from zero throughout — a record written without an extension
does not claim the learner spent no time. The reading is refused for a problem with no
timer, because the clock starts when a problem is *announced* and telemetry must not be able
to start one.

Schema change, so design §5.2 is amended in the same branch, with ADR
`2026-08-10-sensor-observations` carrying the accepted costs: `focusedSec` is only as good
as the extension, and `sawQuestions` says a tab was opened, not that help was taken.

## [2026-08-10] #123 — the English-only guard was reporting on a search it never ran ✅

`constitution guards` printed `ok  no Korean prose in Kotlin comments` on two green trees
that contained exactly that: the branch `f4f77b3e` and, after the squash, `main` at
`c5d2db51`. The guard did not miss the file. It never searched.

Reproduced in an `ubuntu:24.04` container against this tree. `[가-힣]` is a multi-byte
character range and `git grep` resolves it against the locale, which agreed with nobody —
in a bare `C` locale it matched **1006** English comments, on the runner's `C.UTF-8` it died
with `fatal: Invalid collation character` (exit 128) and printed nothing, and only the
author's macOS `en_US.UTF-8` produced the two real hits. `|| true` then made exit 128
indistinguishable from exit 1, so a crashed search read as a clean tree. The check had been
vacuous on Linux since the day it was written.

The local gate missed it for a second reason: `git grep` reads the index, the gates were run
before `git add`, and a file that is not yet tracked is a file the guard has never seen.

Fixed by matching the UTF-8 bytes of the Hangul block under a pinned `LC_ALL=C`, by treating
`git grep` exit > 1 as a guard failure instead of as silence, and — the part that
generalises — by making the guard prove itself on two known comments before its silence is
believed: one that must match, one English comment using `§ — →` that must not. Every fix
considered here, including the two that were rejected, would otherwise have "passed" while
doing nothing.

Scope widened where it was already leaking: `--untracked` closes the pre-`git add` hole, and
`*.js` joined the pathspec because `extension/sensor.js` carried the same Korean prose with
no guard over it at all. Shell scripts stay out on purpose — `guards.sh` quotes the measured
protocol literal `실패 (시간 초과)` to explain why literals are legitimate.

All four failure modes were exercised rather than argued: cannot-detect, over-matches,
search-crashed, clean. The guard fails on the tree that shipped green and passes after the
two comment sites are translated, identically on macOS and on Ubuntu under `C`, `C.UTF-8`
and `en_US.UTF-8`. ADR `2026-08-10-guards-must-prove-they-ran`.
## [2026-08-10] #122 — the record repo ignores its .ps wholesale ✅

The template's `.gitignore` named state files one at a time — `session`, `cookies*`,
`catalog.json` — so everything the server learned to write afterwards was committed by
default. Proved with a real `git add` in a scratch repo: the old rule staged `watch-token`,
`timers.json`, `backup.json` and the whole raw-frame queue including #99's `recorded/`
retirement area. One of those is a credential.

The enumeration was the bug, not the omissions: a list that must be extended whenever the
server writes something new is a list that will be wrong, and it already was. `.ps/`
wholesale, and the comment says why so nobody helpfully re-itemises it.

## [2026-08-10] #126 — the state files move to where the design always said they were ✅

Design §5.1 draws `.ps/` inside the record repository, four `under(recordRoot)` factories
were written for it, and `FileRawSessionLog`'s KDoc asserts it outright. Production called
none of them: `raw-dir`, `timers-file` and `backup.state-file` were CWD-relative, so state
landed wherever the process started. Measured here — the record repository held no `.ps/` at
all while the project checkout held four raw frame files, five running timers and the backup
marker. Docker hides it because the working directory is a mount; natively, starting from
another directory presents an empty raw queue and loses a grading that cannot be replayed.

The three properties are gone rather than re-pointed. A raw-frame queue that can be aimed
somewhere other than the records it is a queue for has no correct second value. They also
used `Path.of` where every other consumer of a configured path used `ConfiguredPath`, so the
shipped default `~/ps-records` would have made a directory literally named `~`.

**`session` and `watch-token` stay outside, and now say why.** They are credentials; the
record repository is pushed. "State lives with the records" is exactly the kind of rule
someone later tidies into "all of it does".

**The hazard the move created, and the guard for it.** Reconciliation is `git add --all`, and
`.ps/raw/recorded/` is a copy of every `attempts/00N.raw.jsonl` — ⚠️ **wrong, corrected by
#128 below: it is the reverse.** A repository whose
`.gitignore` predates #122 names `.ps/session` and `.ps/catalog.json` one at a time and
ignores none of this, so moving state in would have committed the whole capture history a
second time and pushed it. `RecordRepositoryIgnores` appends the one line at startup — at
most once, logging rather than throwing, leaving the rest of the user's file alone.

Proved against real git rather than argued: `RecordWriterGitTest` now puts raw frames where
production does and runs the startup rule, so its existing "what does a commit carry"
assertions became the proof. `git add --all` commits `.gitignore`, `log/submissions.jsonl`
and the attempt file — nothing under `.ps/`.

A side effect worth naming: `RecordRepositoryLock`'s KDoc had a paragraph headed "What it
does not cover", and what it did not cover was `.ps/`. Two instances sharing a raw queue and
a timer document was unguarded; the state is now inside the repository the lock already
claims. ADR `2026-08-10-state-beside-the-records`.

Still owed: this machine's own migration. The live state sits in the project checkout and
nothing moves it — deliberately, since the repository has no other users yet.

## [2026-08-10] #128 — the server was writing a false reason into the user's own repository ✅

#126's ignore rule carried an explanation, and the explanation was backwards. It said
`.ps/raw/recorded/` duplicates every `attempts/00N.raw.jsonl`. `RecordWriter.retireRaw` is
`if (copied) discard else setAside`: a submit's frames are copied into the attempt file and
the **source is deleted**, while a run — which has no attempt file, `rawPath` null since
#99 — is the only thing set aside. So `recorded/` holds runs and nothing else, and each of
those files is the sole copy of its frames rather than a duplicate of anything.

Measured after migrating this machine: three files in `recorded/`, all three `"action":"run"`,
and four `*.raw.jsonl` under `attempts/` with no counterpart there at all.

The rule was right and its reason was wrong, which is worse than it sounds: #126 wrote that
reason into a `.gitignore` inside the record repository, so a document asserting a property
the code does not have had been placed in a file the user owns. The correct justification was
sitting in [[decisions/2026-08-08-run-raw-sessions]] the whole time — a run "gets pressed
dozens of times while writing code", so committing them is the inflation design §5.1 exists
to prevent, which is exactly why that ADR rejected putting runs in the repository.

`RecordWriterTest` now pins both sides: a run lands in `recorded/`, and a submit's source
does **not**. The claim cannot drift again without a test failing.

Two documents amended rather than rewritten. `2026-08-10-state-beside-the-records` carries a
⚠️ correction in its Outcome. `2026-08-08-run-raw-sessions` said `.ps/` "sits outside the
record repository in both supported layouts", which #126 quietly made false — it now carries
a ⚠️ note that its substance is unchanged and only the mechanism keeping run frames out of
the history has moved, from *being elsewhere* to *being ignored*.

Also this session: the live migration. State moved to `<records>/.ps/`, the two credentials
stayed in the checkout, five stale timers (4–6 days old, which would have put a wrong
`elapsedSec` on the next record) and an unread `tag-vocab.json` were deleted. Before the first
boot `git check-ignore` called the state committable; after it the repository was clean, and
the leftover raw session was recognised as already recorded (`duplicates=1`) rather than
recorded twice.

## [2026-08-10] #130 — a reconciled duplicate never left the work list ✅

Found by watching a running server rather than by reading code. Every boot logged the same
two lines:

```
Reconciling 1 raw session(s) left behind by an earlier run
ReconcileReport(recorded=0, duplicates=1, failed=0, skippedLines=0)
```

`.ps/raw/20260806T065702641Z-181951.jsonl` had been re-read on every restart since 2026-08-06.

Retirement lives **inside** `RecordWriter.write`: a session is discarded or set aside as part
of writing its record. A duplicate is dropped by capture key before the writer writes
anything, so it retires nothing, and nothing else takes the file off `unprocessed()`. This is
the growing boot cost `2026-08-08-run-raw-sessions` removed from the live path and left in
this one — the ADR's Outcome now records that.

Set aside rather than deleted: the frames are an original and that rule has no exception for
a duplicate. Same `recorded/` directory as the run path, so no second notion of "already
handled" was invented.

**The first version of the test was vacuous and the fix looked verified.** Asserting that
`recorded/` held one file passed with `discard` too, because the run fixture's *first* pass
had already set a file aside there. The discriminating case is a **submit**: its first pass
copies the frames into the attempt file and discards the source, so `recorded/` does not exist
until the duplicate pass creates it. Swapping `setAside` for `discard` now fails the test,
which is the only reason to believe it tests anything.

## [2026-08-10] #132 — the review queue, and the boundary it had to settle first ✅

The first tool that answers "what should I do next". Everything before it collects.

**A conflict had to be resolved before any code.** Design §6.4 asks the server to compute
`confidence` and a review date; CLAUDE.md's forbidden list says "rule-based analyzers inside
the server — interpretation is the AI's job". Both were written the same week and neither
mentions the other.

The boundary that makes them decidable: **diagnosis is a claim about the learner, scheduling
is a claim about a date.** "You are weak at DFS/BFS" is the misdiagnosis the wiki already
records; it stays the AI's job. A date derived from recorded facts by a published formula
says nothing the records do not. The server may do the second on two conditions — every item
ships the facts that produced it, so the schedule is arguable rather than asserted, and the
formula is written where a reader will find it. ADR
`2026-08-10-scheduling-is-not-diagnosis`.

**The formula, and what it refuses to do.** Penalties from attempts (0–3) plus the questions
tab being opened (+2), banded into 60 / 21 / 7 / 3 days. It reproduces both worked examples
§6.4 states, which is the only calibration in existence — the numbers are chosen, not
measured, and the ADR, the tool description and `mcp.md` all say so.

- `focusedSec` is **reported and never scored**. Calibrating it needs a per-level distribution
  of how long problems take; there are four recorded problems. A level-blind threshold would
  call 45 minutes on a Lv3 a struggle and on a Lv0 normal with the same number.
- Performance versus expectation stays out — that is §6.6, and half of it here would leave two
  places computing the same idea differently.
- **Absence never buys confidence.** A pass with no extension watching cannot reach 60 days.
  Reading "we were not watching" as "no help was taken" is the one error direction that pushes
  a shaky problem two months out.

**Two things earned tests rather than comments.** Attempts are counted from the *previous*
pass, because counting the whole history would make a re-solve look shakier than the first
pass and the queue would never release anything. And due-ness is decided in **the offset the
pass was recorded in** — the container runs UTC, measured on the running image, while the
learner is nine hours ahead, and a global timezone setting would be a second place for the day
boundary to be wrong. Reverting either makes a test fail; both were run in both directions.

`review_queue` is the fifth MCP tool. The catalog test that asserts the exact tool list caught
README, `bootstrap.md` and `mcp.md` still saying four — the guard working as intended.

## [2026-08-10] #134 — slow_passes: passing is not the end ✅

Design §6.5. `runTime` arrives per testcase and a pass far slower than its neighbours means
the intended solution was missed — which an efficiency test in a real exam scores as an
outright fail.

**The unit was measured, not assumed.** `run_time` is a decimal string of milliseconds, and
the protocol frame states it itself: the same message carries `"run_time":"0.01"` beside a
`msg` spelling the same number as `(0.01ms, 75.3MB)`. The recorded passes here read 68–92 ms
for Lv0 Java, which is JVM startup and consistent with that.

**The same boundary decided it as the review queue.** §6.5 asks for "markedly slower than
same-tag, same-level problems"; that needs peers, and there are two passed problems. So no
baseline is applied — the whole distribution comes back in one call, slowest first, and the
ordering *is* the comparison. `thresholdMs` is there for a caller who already knows. The ADR
`2026-08-10-scheduling-is-not-diagnosis` records this as its second application rather than
being restated in a new one; two features now share one boundary.

**The absence rule mattered more here than in the review queue.** A pass with no timing —
every SQL pass, since the protocol sends no per-case time at all, plus any case lost to a
timeout — is excluded from the ranking and **counted** in the answer. Sorting a missing
reading as zero would put the problems we know least about at the fast end of a list whose
whole subject is speed.

The guard caught my own KDoc quoting the Korean protocol string as evidence for the unit.
It is right to be blunt: the fix was to state the measurement without the quotation rather
than to widen the guard. Sixth MCP tool; the catalog test caught the three documents still
saying five.

## [2026-08-10] #136 — a number that was never measured was being served as one ✅

Every submission the MCP server returned carried `"hintLevel": 0`. Confirmed live against the
running server before anything was changed.

`hintLevel` appeared twice in `src/main`: its own declaration, defaulted to 0, and nothing
else. No code ever wrote it — the design assumed hints would arrive through a `mark_hint` tool
that was never built, and MCP is read-only by decision, so nothing could write it now either.
All 14 records on this machine hold 0.

Not an unused field: a **placeholder that reads like a measurement**. An AI asked "does this
learner lean on hints" had every reason to answer "no, zero across the board" — confidently,
from nothing. That is exactly what `concepts/assumption-vs-measurement` was written about, and
it was live on the wire rather than hypothetical.

`sensor.sawQuestions` (#121) is the measured replacement and, unlike `hintLevel`, is **absent**
when nothing observed, so it cannot be mistaken for a reading. It is a boolean where the design
wanted a level — a real loss, and still the better trade.

Removal is safe for the history because `SubmissionRecordJson` sets `ignoreUnknownKeys`, but
that is a claim, so it is now a test: a stored line still carrying `hintLevel` decodes to the
same record. Losing the history to a field removal would cost incomparably more than the field
ever did.

Design §5.2's record schema and §6's hint-dependence row now say what exists. The
`2026-08-10-sensor-observations` ADR had left this open — "removed or wired when the review
queue lands" — and its Outcome records which way it went.

## [2026-08-10] #138 — three §6 features were mislabelled as pending server work ✅

Not code. Measurement against the running server and the shipped catalog, so nobody builds
the wrong thing later.

**§6.2 company profiling needs no server work.** `list_problems` already returns `part` and
`tags` per problem, so one unfiltered call hands a client all 689 and the grouping is theirs.
Demonstrated: `list_problems{part:"2018 KAKAO BLIND RECRUITMENT"}` → 12 problems,
implementation 8 · string 5 · simulation 3 · bruteforcing 2 · sorting 2. That is the whole
feature, in one call, computed by the caller — which is the line §6's own first sentence draws.

**And its premise does not hold.** The design calls `partTitle` "perfect as a company × period
axis". Measured: 49 values, of which ~49% are learning tracks (코딩 기초 트레이닝 124, 연습문제
114, 코딩테스트 입문 100) and ~13% SQL topics (SELECT 33, GROUP BY 24, JOIN 12). Real exam sets
are the minority. Grouping them into companies means a hand-maintained map over labels
Programmers can change at will — the same reason CLAUDE.md forbids inventing a tag vocabulary.

**§6.1 exam mode and §6.9 retrospectives are blocked on a decision, not on effort.** Both
write — `exam_start`/`exam_finish`, `append_retro` — and MCP is read-only by an accepted
decision that exists for prompt-injection reasons: an AI holding the token cannot alter a
record however it is prompted. §6.9's "the server only provides the hook" is precisely the
hook that decision closed. Reversing it is the owner's call and needs its own ADR.

§6 now carries a four-state table — shipped, already deliverable, needs a decision, deleted —
because "not built" was hiding three different situations.

**The read-only analysis half is therefore complete.** Everything left in §6 either needs no
server or needs a decision I should not make alone.

## [2026-08-11] #140 — the user-facing documents described a server from four features ago ✅

Done before translating rather than after, because a stale document translated is two wrong
documents instead of one.

Every claim checked against the running server. `README.md` still said "the analysis half is
not [built]" after `review_queue` and `slow_passes` shipped. `mcp.md` opened with "Three
tools, all read-only" and its "what is not built" list still named both of the tools it now
serves — plus `company_profile`, `performance`, `stuck_testcases` and `attempt_diff`, all of
which are already deliverable, and `mark_hint`, whose field #136 removed.

"Not built" was hiding the same four situations design §6 was hiding until #138, so the list
now separates them: **already deliverable** (with the tool and field that delivers each),
**needs a decision** (everything that writes), **deleted**, and **genuinely absent**.

Verified live rather than assumed: `get_problem` returns `diffFromPrev`, `testcases`,
`acceptanceRate` and `level` on every record, and `list_problems` returns `part` and `tags`
on all 689 — which is what makes four of the sketched tools unnecessary rather than pending.

## [2026-08-11] #142 — Korean for the user-facing half, and a guard for the reason it was refused ✅

The owner asked for Korean docs. `2026-08-04-english-only-artifacts` had already considered
exactly that as its option B and rejected it in one line — "every page twice; guaranteed
drift". **That objection is correct**, and in a single-language repository 2026-08-10 alone
produced three live examples of it, the last cleaned up in #140 that morning. So the reversal
could not be a preference for the newer goal; it had to answer the objection.

**Split, not reversal.** The original rationale — "the push gate's stderr is read by
strangers" — is an argument about contributors, and it survives untouched. Five *user-facing*
pages get a Korean twin; `CLAUDE.md`, development rules, the protocol doc, specs, the wiki,
commits, comments and tool output stay English-only. The old ADR is marked superseded **in
part**, not replaced.

**Drift is now a build failure.** Each twin declares the blob hash of the page it was
translated from on its first line, and `guards.sh` recomputes `git rev-parse :<source>`.
Editing an English page without touching its twin fails the build — the only thing that would
have caught any of the three drifts.

Hashes rather than commit ancestry, deliberately: CI checks out at `fetch-depth: 1`, so an
ancestry guard would pass vacuously on every shallow clone — which is precisely how the
English-only check itself sat broken for weeks (#123). A blob hash needs no history, and is
read from the index so a dirty workspace cannot satisfy it.

Proved in four states before being trusted, having learned that lesson twice this week:
source moved → FAIL naming the expected hash; in sync → pass; marker missing → FAIL naming the
required line; twin naming an untracked source → FAIL. Verified on macOS and in an
`ubuntu:24.04` container under the CI locale.

`README.ko.md` ships in the same change so the mechanism is proved on a real pair rather than
on a promise. Four twins remain: bootstrap, mcp, extension, the ps-records template.

## [2026-08-11] #144 — the remaining four Korean twins ✅

`bootstrap.md`, `mcp.md`, `extension/README.md` and the `ps-records` template now have twins.
Five pairs, all markers matching, and the guard still isolates a single drifted pair —
bumping `bootstrap.md` alone names `bootstrap.ko.md` and nothing else.

**Two English pages were wrong and were fixed before being translated**, on the same
principle as #140:

- `extension/README.md` said it "reads five identifiers" and sends "two fields". It sends
  **four** — `lessonId` and `language` from the page (#114), `focusedSec` and `sawQuestions`
  measured by the extension itself (#120). Checked against `sensor.js`.
- The same page told the reader to paste `.ps/watch-token` "from your record repository".
  The token lives in **this repository's checkout**; #126 kept it out of the record
  repository precisely because it is a credential and that repository is pushed. Verified:
  `~/Desktop/ps-records/.ps/watch-token` does not exist.
- `bootstrap.md` contradicted itself — its opening said "there is no browser extension in
  this repository" while its closing section said the extension was verified in a browser on
  2026-08-10. The opening was four days stale.

`template/ps-records/README.ko.md` is a special case worth noting: the template is copied
into the **user's own** repository, so the twin travels with it and the user keeps whichever
they read.

## [2026-08-11] #147 — the badge that meant "working" was the only one you could not see ✅

Reported from a live browser: the tooltip read `watching lesson 181947 in java (refreshed)`
— the server had accepted — while the toolbar showed nothing at all.

A badge background is painted **behind its text**, and `watching` was the empty string. So the
green was never drawn, and the one state meaning "it is working" was the only state with no
visual: a working sensor and an unloaded one looked identical. Both READMEs documented it as
"green, empty", which is not a thing a user can see.

Every state carries a glyph now — `●` · `!` · `×` — and "no badge" is documented as its own
meaning: the content script never ran. Badge text is pinned white, because Chrome picks black
on the orange and that reads as a disabled control rather than a warning.

**The extension also had no icon.** No `icons` key, no image files, so Chrome drew the grey
letter tile every icon-less extension gets. There are real icons at 16/32/48/128 now, with the
generator committed beside them so the mark can be changed rather than redrawn.

The mark is a check whose descending arm is red and ascending arm green — this tool records
the failures as well as the pass, which is the one thing BaekjoonHub structurally cannot do. A
plain green check would have said what every other extension says. Drawn at 8× and
downsampled, because Pillow does not antialias and a hard-edged 16px check is a smear.

And the manifest still repeated the "reads five identifiers" claim #144 corrected in the
README — the guard reads Kotlin comments and `*.ko.md` pairs, so a JSON description was
outside everything that checks.

## [2026-08-11] #149 — only the first submit of a problem could ever be recorded ✅

The owner solved lesson 181947 on the current build — four runs and a submit. **The submit
passed and was dropped**, along with two of the runs.

Its capture key was `7f0beaa55092fc63`: the key of a submit recorded on 6 August **with
verdict WRONG**. An algorithm submit terminates on `finish`, and the two `finish` frames are
byte-identical — `{"action":"submit","type":"finish"}` beside the channel identifier, carrying
no verdict, no score, no timing. So the key was a **constant per problem**, and every submit
after the first collided with it.

The record repository had been saying this for five days. Every problem in it had exactly one
submit, and I read that as someone who passes first try. It was the defect — in a tool whose
first paragraph is "of 449 attempts, only the 43 successes are knowable".

**Fixed by digesting every accepted frame**, in order, verbatim. Both properties hold: a
replay derives the same key, because the raw log keeps frames verbatim and both paths skip
the same non-fact lines; two real gradings differ, because testcase frames carry `run_time`
and `memory_size`. Verified against all nine captures on disk — nine keys, no collisions,
including two passing runs five seconds apart.

The residual is stated rather than engineered around: two gradings whose every frame is
byte-identical still collide, and **SQL is where that is plausible**, since it sends no
per-case timing at all.

Nothing was lost. The writer drops the *record*, not the capture, so all four stranded
sessions were still in `.ps/raw/` and still on the work list.

The regression is built from the two measured `finish` frames rather than a hand-written pair,
and reverting the basis to the last frame fails it. Today's submit is committed as a scrubbed
fixture — `surveyUrl`, `finishModalLink` and both ratings substituted per dev rules §7.3.
ADR `2026-08-11-a-grading-is-its-whole-session`.

## [2026-08-11] #151 · #152 — a compile error was neither classified nor captured whole ✅

Two defects from one live capture — lesson 181946, a Java solution that did not compile. Kept
together because neither is visible without the other: fixing the truncation alone still
leaves the record UNKNOWN, and fixing the verdict alone still splits the grading in two.

**The run was cut in half (#152).** The run path emits one `error` frame per diagnostic and
then a `result` (protocol §7), and `TerminationRule` ended the stream at the first error —
directly beneath a matrix that already said `(RUN, ALGORITHM) -> RESULT`. The second error and
the result arrived 0.3 s later to a closed grading and were filed under `orphans/` as *"its
start was missed"*, which is the one thing that had not happened. `error` now ends every cell
except that one; the cached-result submit still ends on it (§13.2).

**The compile error was not a verdict (#151).** `VerdictResolver` opened with
`if (testcases.isEmpty()) return null` — before reading the error text it had already been
handed, and before reaching the `compilerDiagnostic` regex three lines below. A compile
failure runs nothing, so it reports no testcases, so one of the five verdicts the README
advertises was filed as UNKNOWN. It also tripped the drift warning on every occurrence, which
trains you to ignore the one alarm that exists to catch Programmers rewording something.

**The trap was the cached result**, which is also a terminal error frame with no testcases.
Reading its text as a failure would invent a RUNTIME_ERROR the learner never had, so the
resolver asks `UnknownReason.matching` first and a recognised unknown stays unknown.

A test had been pinning the defect as intended behaviour — *"the trailing error … a measured
frame belonging to no grading"*. It was not an orphan; it was the second diagnostic of the
same run. The capture had been split in half since the run path was written, and a test
explained the halves instead of questioning them.

ADR `2026-08-11-a-failing-run-ends-at-its-result`.

## [2026-08-11] #154 — error terminates nothing, and the exception was the rule ✅

Found by driving Chrome directly: wrote a compile error, ran it, fixed it, ran, submitted, and
submitted the same code again.

**The three earlier fixes held.** Compile error → `COMPILE_ERROR / JUDGED`; the fixed run →
`PASS 2/2`; the submit → `PASS 18/18`, committed as
`[Lv0] 두 수의 합 구하기 — PASS (18/18, attempt 1, 8m50s)`. Zero warnings throughout.

**The resubmit reproduced #154 exactly.** A cached-result submit reports its error and then
**grades anyway** — `start · error · test_group · testcase ×18 · result_lesson_challenge ·
finish`. Closing at the error recorded UNKNOWN and filed all twenty-one remaining frames,
eighteen of them passing testcases, as orphans.

#152 had scoped the change to `(RUN, ALGORITHM)` because that was all that had been measured.
The same defect was waiting on the submit path. `error` now terminates **nothing**; the matrix
is the only rule, and it had the right answer both times.

**Two things that mattered more than the fix:**

`algorithm-cached-result.jsonl` was never the protocol — it was a capture this bug truncated,
and the fixtures README described it as "no verdict frames at all" as though that were a
measured fact about Programmers. It is relabelled as the half it is and superseded by
`algorithm-cached-then-graded.jsonl`, reassembled from the two halves of one grading and
verified by their shared channel identifier.

The orphan warning said "its start was missed" — **wrong every one of the eleven times it
fired today.** Every one was the tail of a grading closed too early. A diagnostic that names
the one thing that did not happen is worse than none; it now reports what is known and offers
both explanations without choosing.

**My own mistake, recorded because it cost the owner's account.** Mid-test I pressed Run and
then `cmd+a`, but focus had left the editor, so the page was selected and the typing never
reached the code. Two more runs of the same broken code went out (`03:57:39 INCOMPLETE`,
`03:57:41 COMPILE_ERROR`). Click into the editor before selecting.

## [2026-08-11] #156 — the badge now says whether the grading was recorded ✅

The gap this closes is the one that let #154 hide for twenty minutes: the page announced a
pass, the server recorded `UNKNOWN`, and nothing on screen disagreed with anything else. The
badge could say "the sensor is talking to the server" and nothing about whether that produced
a record.

`POST /watch` already answers on every heartbeat, so it now carries the newest grading
recorded for that lesson — action, outcome, verdict, testcase counts, when. Absent when there
is none, which the badge reads as a different thing from "recorded but unclassified".

| badge | meaning |
|---|---|
| `●` green | watching; nothing recorded for this problem yet |
| `✓` green | the last grading **was recorded**, whatever its verdict |
| `?` purple | recorded, and the server could not classify it |

**A recorded wrong answer is `✓`.** The badge answers "is the tool working", not "did you
pass". Recording a failure is the whole point of this tool, and a red mark there would teach
the user to read their own wrong answers as a broken sensor.

`?` is the state that did not exist. It is what a lost grading looks like, and it would have
shown within thirty seconds on 2026-08-11.

**No spinner, deliberately.** BaekjoonHub polls the results DOM for one because it must decide
*when to upload*; we record from the broadcast stream and need no such trigger. Thirty seconds
of heartbeat latency is fine for a verification signal and useless for a progress indicator —
which is another way of saying the heartbeat is the right channel for this and the wrong one
for that. Doing better would mean watching the page, which this extension does not do.

The guard caught three of my own comments quoting the Korean UI string in prose. Rewritten in
English rather than widening the guard.

## [2026-08-11] #157 — two gradings could share one raw session file, and one destroyed the other ✅

Measured while testing Python on lesson 120805. Two channels for one problem opened a grading
**in the same millisecond**, and the session name was `<stamp>-<lesson>.jsonl` and nothing
else. Both wrote into the same file — the capture on disk holds `start ×2 · error ×4 ·
result ×2`, two gradings interleaved — and on retirement one `Files.move` replaced the other.

The originals are the thing this project promises never to lose (dev rules §2.4), and a
millisecond of clock precision was the whole guarantee.

Names are **issued rather than computed** now: a candidate already handed out by this log, or
already on disk from an earlier run, takes a discriminator until it is free. Deterministic —
no clock precision assumed, no randomness for a test to work around, and the retired directory
is checked too so a restart cannot reissue into a copy of a grading already recorded.

**The discriminator had to reach the work-list walk as well.** `NAME` parsed
`<stamp>-<lesson>.jsonl` exactly, so a discriminated session would have matched nothing and
dropped off the reconciler entirely — a quieter loss than the collision it fixes.

The file is deliberately not created at `start`. Reserving by touching an empty file would
leave a frameless session on the work list whenever a process died between the two, and a
capture that fails every reconciliation forever is worse than what this fixes.

## [2026-08-11] #158 — one grading became one record per open channel ✅

Measured on lesson 120805. The problem was opened in Java and then in Python3, both
subscriptions stayed live, and a **single Python run produced two records** — one labelled
`java`, carrying Python's traceback. A record for code that was never run is the worst thing
this tool can produce, and it took nothing unusual to make one: switching the language tab is
ordinary use.

The frames escaped the duplicate check because each subscription receives the broadcast with
**its own identifier stamped in**, so the two byte streams differ by the `language` field and
key differently.

**One channel per problem now.** A language switch supersedes the channel it switches from,
reported through the `evicted` path the registry already had for capacity eviction, so the
caller unsubscribes it on the socket exactly as before.

The owner's point stands and is preserved: solving one problem in Kotlin and then in Java is
**two gradings and stays two records** — someone whose target company forbids Kotlin has to
practise both. What this forbids is one grading becoming two, which is a different thing.

Not established: *why* the broadcast reached both channels. The Java channel's raw session was
the one #157's collision destroyed, so its identifier was never seen. The fix does not depend
on knowing — with one channel there is nothing to duplicate.

## [2026-08-11] #159 — the capture key was answering a question it cannot answer live ✅

Measured on lesson 151136: the same SQL query submitted twice, and the second was dropped as
a replay. SQL frames carry no `run_time` and no `memory_size`, so the same query is
**byte-identical** down to the last character. Java escapes only because its timings jitter,
which is luck rather than design.

The key was doing two jobs. It is good at one: *a replay of stored bytes must not write a
second record*, which is what makes reconciliation safe. It cannot do the other — *is this the
same grading?* — because live, all it sees is whether the bytes match.

So the paths are separate now. `write` is live and asks nothing; `replay` is reconciliation
and asks the index. Both still populate it.

Two things make dropping the live check safe, and both were established this week rather than
assumed. The socket does not redeliver — a reconnect **loses** what was broadcast meanwhile,
which the log has always said. And the one path that did deliver one grading twice was two
channels on a problem, closed at the subscription in #158.

Four tests had been pinning live dedup. Three were modelling a replay and now say so. The
fourth asserted that feeding one fixture twice yields one record — the exact reading that
discarded the second SQL submission — and now asserts two, because that is what two live
gradings are.

## [2026-08-11] #160 — a Python compile failure was filed as a runtime error ✅

Measured on lesson 120805, a `def` missing its colon:

```
  File "/solution.py", line 3
    def solution(num1, num2) return num1 // num2
                             ^^^^^^
SyntaxError: expected ':'
```

Recorded as `RUNTIME_ERROR`. The classifier had one pattern — `:\d+: error:` — and that is
**javac's** shape. Nothing in the code said so, so it read as "a compile diagnostic" rather
than "a Java compile diagnostic", and every other language fell through the default.

It is a list now, one entry per toolchain whose output has actually been captured, and the
comment says that measurement is the rule rather than an accident of effort. A language whose
compiler is not in it lands as RUNTIME_ERROR — guessing at a format buys nothing when it is
right and misclassifies when it is wrong.

`IndentationError` and `TabError` are deliberately absent: they are SyntaxError subclasses that
print their own names, neither has been captured, and each is one line when it is. Saying so
in the comment is the difference between a gap and an oversight.

The second test is the one that matters more — a Python traceback with no compile diagnostic
(`ZeroDivisionError`) must stay RUNTIME_ERROR, so the new pattern cannot swallow the case it
sits next to.

## [2026-08-11] #161 — the wiki's raw layer had been empty for six days ✅

`raw/sessions/` was last written **2026-08-05**. `log.md` recorded **33 ingests**.

The practice had drifted from running `/wiki-ingest` to writing an ADR inline and appending a
log line. The log entry is the visible half of the ritual, so it kept being copied; saving the
raw has no immediate consumer, so it stopped. Six days of the heaviest work in the project left
nothing in the layer the schema calls the source of truth (§1) — and this session did the same
thing six more times before being asked whether the skill had run.

Two ADRs went further and cited raw sessions **that were never written**
(`2026-08-07-adversarial-review.md`, `2026-08-10-sensor-verified.md`). Both citations were
removed rather than back-filled: a raw session reconstructed from the wiki page that supposedly
sourced it is not a source, it is the fixture-README failure again.

Ingested: the 2026-08-10~11 session raw, `concepts/tests-that-explain-defects` (the pattern
found three times that afternoon — tests and fixtures encoding defects as facts), and a source
stub. Nine pages picked up the raw as a source; `2026-08-11-a-grading-is-its-whole-session`
records that its first accepted cost came due within hours as #159.

`scripts/guards.sh` §6 now fails the build on a `sources:` entry that does not resolve —
negative-tested on both entry forms, 77 citations currently resolving. It is scoped to
`sources:` on purpose: checking `[[...]]` targets would need an exception for the schema
document's own examples, and a guard with an exception list is the kind that gets muted.

**The same drift in this file.** The last five entries above named numbers that were guessed
rather than allocated: **no issue was ever opened for #156 through #160**, which the flow
requires (CLAUDE.md, "no work without an issue"). Each heading has been corrected to the PR
that actually landed the change — the one number that exists — so `#158` now means PR 157
rather than an issue nobody created, and `#162`, which pointed at nothing at all, is gone.
Prediction had been substituting for allocation, which is how `raw/sessions/` emptied too.

## [2026-08-11] #163 — the six empty days, back-filled from the transcript ✅

#161 closed the wiki gap by admitting it. This closes it by filling it.

**Where the material was.** The owner spotted files accumulating in the central wiki
(`~/Desktop/llm-wiki/`) and asked. The mechanism is a `PreCompact` hook that snapshots the
session transcript — and it writes to a hardcoded `$HOME/Desktop/llm-wiki/raw/inbox`, while the
rule agreed for this project on 2026-08-04 was *repo-local when the repo has `docs/llm-wiki/`*.
This repo has no `raw/inbox/` at all, so that half was never built and four compactions' worth of
conversation went where this project never looks. **Nothing was lost** — the original is still
under `~/.claude/projects/` and is a superset of the dump.

The owner's stated reason for building that harness, the same day, is the sharpest line in the
record: *"auto-compact가 진행되면 대화 내용이 날라가고 만약 wiki-ingest를 안했을때 그대로 손실되는
문제가 있거든 그걸 강제하고 싶은거야."* The harness preserved the material and forced nothing,
because preservation and ingestion are separate steps and only the first was automated.

**What was written.** Five raw sessions and five source stubs, from the transcript, the git
history and the PR record: 2026-08-05 afternoon (#14–#40), 2026-08-06, 2026-08-07 (the four-critic
adversarial review), 2026-08-08/10 (the sensor proven), and the provenance page for this
back-fill. Sixteen pages picked up a real source; the two citations #161 deleted are restored,
now pointing at files that exist. 102 citations resolve.

**The find worth more than the pages.** critic-pipeline's 2026-08-07 verdict already said it:
*"each currently has a test that walks past the defect without asserting on it"* — with a worked
example (the ping test stubbed the layer that swallowed the ping, and asserted the idle reconnect
as desired behaviour). The four defects were fixed and the sentence went with them. Four days
later the same pattern cost five more. `concepts/tests-that-explain-defects` now carries it:
**a finding stated inside a fix is not recorded.**

**What this is not.** These pages are honest but not equivalent to same-day ingests — the
assistant's in-turn reasoning is largely absent, and the selection was made six days later by a
participant. Reconstructing from the wiki pages that cite a source stayed refused; the transcript
changed the situation, not the principle. A first pass sliced days on the transcript's UTC
timestamps and would have filed every evening under the following day; KST slicing was required.

## [2026-08-11] #165 — the harness had implemented only the half that stays quiet ✅

#163 back-filled six empty days. Looking at the other two hooks explained *why* nobody noticed.

Three hooks make the "never lose a conversation" harness, and all three were wrong in the same
direction:

| Hook | Was | Now |
|---|---|---|
| `wiki-archive-precompact.sh` (PreCompact) | hardcoded global inbox | repo-local when an ancestor has `docs/llm-wiki/` **and** the path is gitignored; global otherwise |
| `wiki-archive-session.sh` (SessionEnd) | keyed by **timestamp** — a session ending twice was copied whole twice | keyed by session id; also deletes its own precompact twin, which it supersedes |
| `wiki-remind.sh` (SessionStart) | **`exit 0` when the repo has its own wiki** | counts that repo's own inbox and names its own `/wiki-ingest` |

The reminder is the one that explains the six days. Its bail-out cites *"2026-08-04
programmers-tracker 설계 D3"* in its own comment — so the harness knew about the repo-local rule
and implemented only the half that **suppresses** the reminder. The half that would have nagged
us locally was never written. Archiver writing to the wrong place, reminder silent by design,
nothing pointing at the gap. Same shape as everything else this week: the visible half of a
practice survives, the substance does not.

**Nothing was ever pruned either.** `~/Desktop/llm-wiki/raw/inbox` is **2.8 GB / 90 files** —
13 snapshots of one project, 9 of another, one day producing eight ~162 MB copies of the same
session in two hours. Both archivers now retire snapshots older than 14 days
(`INBOX_RETENTION_DAYS`). The inbox is a *copy*; the original stays under `~/.claude/projects/`.

**Repo side (this PR):** `.gitignore` covers `docs/llm-wiki/raw/inbox/` — the enabling condition,
because a transcript is the whole conversation and one `git add -A` would publish it. The hook
checks `git check-ignore` and falls back to global rather than write somewhere committable. Wiki
schema §6 states the rule for any repo adopting this wiki. `/wiki-ingest` now reads the inbox
first, slices by KST, and **deletes what it consumed**.

**Verified live, not argued.** Six routing cases (repo root · subdirectory · no wiki · another
repo that adopted the setup · ignored · not-ignored), the retention sweep on a backdated file,
and the dedup: six writes across two sessions produce **two** files where the old hooks produced
six. Then the real 35 MB transcript of this session was snapshotted into this repo's inbox and
`git status` stayed blind to it. Originals backed up to `~/.claude/hooks/backup-2026-08-11/`.

Not done: the existing 2.8 GB is untouched. Deleting a user's data is not something to bundle
into a mechanism change.

## [2026-08-11] #167 — /watch said `started` for a subscription the judge had refused ✅

The oldest open defect in the project: found as M1 on 2026-08-07, fixed today.

Subscribing is fire-and-forget, so `/watch` returned 200 `started` before the socket had done
anything — the same answer whether it confirmed, was refused, or never opened. What made it
silent rather than merely imprecise is the identity cache: `PageProblemIdentityResolver` caches
a resolved problem **forever**, so for a problem the server has already seen, an expired cookie
never touches the page fetch that raises `UnresolvableProblemException`. It goes straight to the
socket, the judge refuses, and `report()` logged only `cause.javaClass.simpleName` — throwing
away the one sentence that says what to do.

Green badge, 200 on every heartbeat, a warn line that looks like a flaky network, and every
grading lost. The constitution's worst outcome in its purest form.

`watch()` now answers `WatchStatus(outcome, health)` and `/watch` carries `subscription` beside
`status`. Three rules keep it honest, and each has a test:

- **absent means UNREACHABLE, never PENDING** — the optimistic default *is* the defect
- **a failure is cleared only by a frame, never reset per attempt** — the retry loop runs
  continuously, so re-marking PENDING would make a refusal blink out of view every second and
  restore the bug while passing every other test
- **an attempt that ends without a single frame demotes** — the measured ~30-minute silent close
  throws nothing, so nothing else would notice

The badge gains red `!` and it **outranks the record state**: a `✓` from an hour ago is true and
irrelevant if nothing is being watched now. REJECTED and UNREACHABLE stay separate all the way
to the tooltip because they ask for different things — replace `.ps/session`, or wait.

Also settled the same review's MINOR on the same line: the rejection reason no longer embeds the
channel identifier, which was the one place `StoredChannel`'s stated policy was contradicted.

ADR: `decisions/2026-08-11-a-watch-answer-is-not-a-promise`. The Korean twin of
`extension/README.md` was updated with it, marker resynced.

**Not verified live.** The badge path is exercised by tests and the JSON contract by a controller
test, but no expired cookie has been driven through a real browser. That is the honest status.

## [2026-08-11] #169 — the two orphaned gradings, and what could honestly be done about them ✅

Opened the two files. They are not what the work list called them:

| file | contents |
|---|---|
| `181946.jsonl` | a failing run, and **a complete submit grading minus its `start`** — 4 testcases through to `finish` |
| `120802.jsonl` | two failing runs, and a complete submit minus its `start` — 18 testcases through to `finish` |

And the records they produced exist:

```
181946  submit 3  UNKNOWN  0/0     ← the frames say 4/4
120802  submit 2  UNKNOWN  0/0     ← the frames say 18/18
```

So this was never "two gradings are missing". It is **two records are wrong**, which the failure
taxonomy calls the worse half, with the evidence sitting next to them.

**Reconstruction was rejected, and that is the decision.** `SubmitMessage.Start` carries
`testcaseIds`, `exampleTestcases` and the challengeable identity — no later frame repeats them,
so a reconstructed grading is already less than a real one. The deciding objection is sharper:
binding a stretch of an orphan file to the attempt it belongs to is **inference**. The file is
per-lesson and append-only, several gradings sit in it end to end with no separator and no
timestamp, and today's match is unique by accident. Attaching an 18/18 PASS to the wrong attempt
is the thing CLAUDE.md forbids by name.

What was actually wrong was different: they were announced **once**, in a warning on the day, and
nothing mentioned them again. Not at startup, not over MCP. The record has holes and every
consumer believes it is complete — including the AI whose entire job is to diagnose weaknesses
from it.

`RawSessionLog.orphans()` now answers what is stranded, startup reports it at every boot, and
`stats` carries `incompleteHistory` naming the lessons and counting the frames. **Absent when
there is nothing stranded**, so its presence is the signal — the same rule `docs/mcp.md` already
applies to missing fields, one level up: what can be missing is not only a field but the record.

Deliberately a count and a path, never a parse. "3 gradings" would be a claim the store cannot
support without the same inference that was just refused.

ADR: `decisions/2026-08-11-a-hole-in-the-record-is-reported-not-filled`. `docs/mcp.md` and its
Korean twin updated; marker resynced.

Remaining risk: the two records stay wrong, and `incompleteHistory` appears only on `stats` —
`submissions`, `review_queue` and `slow_passes` read the same incomplete history and say nothing.

## [2026-08-11] #171 — a log line can no longer claim a session that was never saved ✅

The original failure of the week, finally guarded. `log.md` recorded 33 ingests over an empty
`raw/sessions/`; §6 catches a citation pointing at nothing, but nothing caught a claim with
nothing beside it.

**No date floor, and that is the part worth reporting.** I said in #166 and #170 that this would
need one — every historical line failing an added rule is the usual reason a guard gets a
grandfather clause. Checked instead: after the #163 back-fill, all seven ingest dates already
have a raw session, so §7 applies to the whole file with no exemption. An exemption list is what
a rule decays into, and the cheapest moment to avoid one is before it exists.

A date is matched from a raw session's **filename or its H1**, because a day's work does not
always get its own file — 2026-08-08 is one question, recorded inside a page named for 2026-08-10
whose heading reads `# 2026-08-08 / 2026-08-10 — …`. That is as much prose as a guard should
read.

Negative-tested in both directions: a log line for a date with no raw fails, and **removing the
date from that H1** makes 2026-08-08 fail — which is what proves the heading is load-bearing
rather than decorative. Canaries cover the two silent deaths: an ingest-date list that parses as
empty would pass any wiki, a raw-date list that parses as empty would fail every one.

ADR: amended `decisions/2026-08-10-guards-must-prove-they-ran` rather than adding a new one —
this is the same decision applied a third time, not a different one.

## [2026-08-11] #173 — a pass belongs to its language, and the layout was never the blocker ✅

**I had this wrong twice.** I reported per-language attempts as blocked on the record-repository
layout — `attempts/001.raw.jsonl` carries no language, so per-language numbering would collide.
Reading the code instead of repeating the claim: `attemptFile` is `attempts/NNN.<ext>` and the
extension already carries the language, and attempt numbers are per problem and monotonic across
languages by design (§5.1), so `001.raw.jsonl` is unambiguous. **No collision exists and no
layout change was ever needed.**

What is language-blind is the analysis. Both calculators grouped by lesson alone, so:

- `review_queue` — a pass in Kotlin scheduled the problem as reviewed, and someone practising
  Java because a company does not offer Kotlin was told they were done with a problem they had
  never once solved in it
- `slow_passes` — kept **one pass per problem, the latest**. A slow Java pass written the day
  after a fast Kotlin one vanished entirely, which is the exact reading that tool exists to
  surface, hidden by the tool

Both now key on `(lessonId, language)`; `ReviewItem` carries the language and the ordering gains
it as a final tie-break. Attempt counting follows the grouping, so Kotlin submits do not make a
first Java attempt look shaky.

The measurement argument is the strong one and has nothing to do with learning: **a runtime
measures the solution you wrote**, so attributing a Kotlin reading to "this problem" and letting
it displace a Java one is losing data, not a judgement call. Whether solving it once carries over
is a claim about the learner, and [[decisions/2026-08-10-scheduling-is-not-diagnosis]] already
settled that the server does not make those.

SQL needs no special case — `mysql` and `oracle` are separate languages, so separate tracks.

Seven tests, four of them for the direction that matters rather than the happy path. ADR:
`decisions/2026-08-11-a-pass-belongs-to-its-language`. `docs/mcp.md` and its Korean twin updated.

Remaining risk: **never exercised on real two-language data** — this machine's history has none
yet. And `stats(groupBy=problem)` still counts by problem, so the two surfaces group differently;
correct in both cases, and a thing a reader has to notice.

## [2026-08-11] #175 — the remaining risk on #168 was the finding ✅

#168 shipped that morning with a stated remaining risk: *"not verified against a live expired
cookie"*. Verifying it undid the claim.

Two `liveObserve` processes on the **identical** channel identifier, running at the same moment,
one reading the real session file and one reading a scratch file containing an invalid string.
One `run` (not a submit) on Lv0 120802, triggered in the browser. The real `.ps/session` was never
modified or printed.

| observer | confirm | pings | broadcasts |
|---|---|---|---|
| valid session | 1, at 1.61 s | 110 | **4** — `run/start`, `run/testcase` ×2, `run/result` |
| invalid session | 1, at **0.49 s** | 160 | **0** |

The unauthenticated socket was confirmed **faster** than the authenticated one, pinged for the
whole observation, and was never rejected. It simply received nothing.

**So `SubscriptionHealth.REJECTED` is dead code for session expiry.** A ping is a frame, health
reaches `LIVE`, the badge is green, and every grading is lost. What #168 still does is real — an
unreachable socket and an attempt ending without a frame now demote and reach the badge, and both
were invisible before — but that is not the failure it was written for.

It also corrects a **measured** section. Protocol §10 said streams are scoped by channel
parameters "not by connection"; both of its verifications used the same valid cookie, so what
they measured was *two sockets, one user*. Streams are scoped by channel parameters **and by the
connection's authenticated identity**. §15.3 records the measurement; §14 replaces the
`reject_subscription` assumption with what was found.

The line worth keeping: the fix made the wrong answer **more confident**. `started` was
obviously uninformative; `subscription: "live"` is a reason to believe. Building on an unmeasured
claim did not leave the confidence where it was, it raised it — so
[[concepts/assumption-vs-measurement]] gains the rule that a failure state must never be wired to
a frame nobody has observed.

Docs corrected: `extension/README.md` and its Korean twin now say the badge **cannot** see an
expired cookie and what to do when records stop while it looks healthy. The #168 ADR carries the
amendment with its old claim preserved as ⚠️ (old).

**Detection is unsolved and is now its own problem.** The socket offers nothing, and §3 records
that the problem page yields identifiers without login, so a 200 there proves nothing either.
Candidates are listed in §14 and none is measured.

⚠️ **A claim in the first version of this entry was wrong (#177).** It said the server was
logging `Refused an unauthorized /watch request` throughout and the sensor was blind. That came
from `--tail 12` showing the line once. Measured: **one** 401 over the container's entire
lifetime, the token is identical in the checkout and the container, `POST /watch` answers
`200 refreshed`, and the run triggered for the measurement was recorded normally
(`120802 java run PASS 2/2`). The extension was working the whole time.

The error is the same one this entry is about: §10 turned "two sockets, one user" into "not by
connection"; I turned one log line into "throughout". The observation was real and the quantifier
was invented — and the claim was believed because it *fitted the story*, which is not evidence.

## [2026-08-11] #179 — an expired session is detectable after all, on the endpoint that answers ✅

#175 left the biggest open risk in the project: from the moment a cookie dies every grading is
lost and the badge stays green, because the socket cannot see it.

**Measured before choosing** — which is the correction this whole thread is about. Four endpoints,
with and without the cookie, three alternating runs (protocol §15.4):

| endpoint | signed in | signed out |
|---|---|---|
| lesson page | 200 | **200** — §3 already says it needs no login |
| `solution_groups` | 200 | 302 → login, but problem-scoped and 401-until-solved |
| `challenges?statuses[]=solved` | 200, 510 B | **200**, 184 B — emptiness ≠ signed out |
| **`open-challenge-activities`** | **200** | **401**, JSON `{"code":"authenticate_user",…}` |

`SessionActivityProbe` maps 200 → ALIVE, 401 → EXPIRED, **anything else → UNKNOWN**, and `/watch`
answers with it beside `subscription`. `UNKNOWN` is never folded into EXPIRED and never cached;
every other answer is held 5 minutes, because the extension posts every 30 s per open tab and
probing on each would be a request every few seconds.

`subscription: live` beside `session: expired` is a real combination, not a contradiction.

**Verified live on both branches**, which is what #168 did not do:

```
real cookie       {"subscription":"pending","session":"alive"}
invalid cookie    {"subscription":"pending","session":"expired"}  + the server's own WARN
restored          {"session":"alive"}
```

The cookie file was copied out, hash-compared before and after, and restored byte-identical. The
failure branch is the one worth exercising and exercising it must not cost the credential.

ADR: `decisions/2026-08-11-the-session-is-checked-where-it-can-answer`. Protocol §15.4 records the
comparison; §14's `reject_subscription` line now points at both measurements. `extension/README.md`
and its Korean twin replaced "nothing can see this" with what now does.

Remaining risk, stated in the ADR: a dead cookie is invisible for up to five minutes; the probe is
traffic Programmers did not ask for (one GET per five minutes while a tab is open); `UNKNOWN` shows
nothing, so an outage and a healthy session look alike from the toolbar; and **nothing notices a
probe that has answered `UNKNOWN` for a week** — which is the shape of failure this project keeps
finding.

Also filed, not fixed here: #180 — `ProblemPageCodeFetcher` still holds a cookie it never reads and
hands it out through a dead accessor, flagged as a MINOR on 2026-08-07. Noticed again while writing
this probe, which deliberately takes no cookie for exactly that reason. Kept out of this PR because
removing it touches four test call sites and two production ones.

## [2026-08-11] #180 — the fetcher held a cookie it never read ✅

A MINOR from the 2026-08-07 security review, still open four days later:

> `fun cookieHeader(): String = cookie.headerValue()` is dead (nothing in `src/` calls it). A
> public accessor handing out the raw cookie; it falsifies `SessionCookie`'s claim that the raw
> value is reachable solely through `headerValue()`. Delete it.

Worse than dead. The class never needed the cookie at all — `KtorPageSource` carries it, and the
fetcher's own comment said so. The property existed only to feed an accessor nothing called.

Noticed again while writing `SessionActivityProbe` (#179), which takes no cookie for exactly this
reason, and kept out of that PR because removing it touches four test call sites and two
production ones.

**The leak test kept its subject by changing what it pins.** It used to plant a value in the
fetcher's own `SessionCookie` and assert the failure reason did not contain it. With no cookie to
hold, that test would pass forever while checking nothing — the failure mode this repository
keeps finding. It now asserts the structural fact instead: **no constructor parameter can carry a
`SessionCookie`.** A second test keeps the message-content check where it still means something.

`CaptureConfiguration` keeps `runCatching { sessions.cookie() }` — that is the "is there a session
at all" guard, and a missing session file is `Unauthenticated` rather than a fetch that will
surely fail. The value simply goes no further.

## [2026-08-11] #183 — nothing said how long the records had been on one disk ✅

A push can fail forever and the only surface was one WARN on the day it happened.
`BackupLog.lastSuccessAt()` knew when the records last left the machine and **nothing read it
except the "is a backup due" check** — not startup, not `/watch`, not MCP.

So an expired deploy key produces: records committed locally, pushes failing, a warning that
scrolled past days ago, and every record this tool ever wrote living on one disk. The stated
motivation is that 406 failures were already lost; a laptop that dies loses the replacement too.

`BackupAge.of(lastSuccessAt, hasRemote, now)` — a pure calculator — and startup reports it at
**every** boot. The distinction that keeps it from becoming noise: **no remote is not a fault.**
The README says pushing needs credentials the tool cannot invent and that without them it still
captures and commits; that is a supported way to run it, stated once at INFO. A remote that
exists and is not being pushed to is a fault, and one that has *never* been pushed to reads
differently from one that has gone stale, because they ask the user for different things.

Tolerance is two days, chosen so an ordinary weekend of not opening the machine does not raise it.

`backupAge()` is separated from the logging so the tests assert a verdict rather than scrape a
logger — a check whose only output is a log line is one nobody asserts on.

**Live findings while verifying**, both worth keeping:

- The record repository is **`ahead 2`** right now: two commits committed locally and not pushed,
  invisible to the user until I ran `git status` by hand. Exactly the situation this closes.
- I nearly reported the daily backup as broken. It had not run this boot, and the backup marker
  was a day old. It is **correct**: the backup is due at 23:00 Asia/Seoul, the container was at
  22:45 KST, so `mostRecentDue()` was yesterday's 23:00 and the last success was 14 seconds after
  it. Not due. Checked the arithmetic before writing it up — which is the day's lesson applied.
- #169's orphan report is live in production: `2 lesson(s) have frames that belong to no grading`.

Remaining risk: the warning fires at boot, so a machine left running for a week does not see it
change. A periodic check would, and is not built — the daily backup tick is the obvious place.

## [2026-08-11] #185 — the staleness warning now survives a machine that is never restarted ✅

#183's own ADR named this: *"It only fires at boot. A machine left running for a week never sees
it change."* The sequence that ended in silence — deploy key expires Tuesday, every night's push
fails, machine never restarted — now produces one warning when it starts and one all-clear when
it stops.

The whole design question was **not becoming noise**. The tick fires 1,440 times a day, and a
warning repeated that often is one nobody reads — the same failure the `NoRemote` distinction was
introduced to avoid. So `BackupReporter` announces on **change of kind**:

- a `Stale` growing from 9 days to 10 is the same news; comparing the number would fire once a day
  forever. The current number is stated at boot, where it is new information
- **recovering is announced too** — a warning with no matching all-clear leaves a reader unable to
  tell a fixed problem from an unreported one
- boot states unconditionally, the tick only on change, and they share one reporter — two
  instances would each announce the same transition

Seven tests on the reporter, all asserting a returned verdict rather than scraping a logger.

ADR: `2026-08-11-a-record-on-one-disk-says-so` amended — its remaining-risk entry is now ⚠️ (old)
with a pointer to what closed it, rather than deleted.

## [2026-08-11] #187 — the other five tools read the same holes and said nothing ✅

#169's own ADR named this: `incompleteHistory` appeared on `stats` alone, on the argument that a
total is where a denominator matters most. True, and not enough — a pass whose frames were
orphaned is **a problem `review_queue` will never schedule** and **a reading `slow_passes` cannot
rank**, and neither said so. `submissions`, `get_problem` and `list_problems` are the same.

**The fix removed a special case rather than copying it five times.** `McpToolInvoker` already
had one wrap point every result passes through — `succeeded(payload)` — so the field moved there.

Two smaller calls came with it:

- **the answer carries counts; the prose moved to the tool descriptions.** A client receives those
  once from `tools/list`, and a full paragraph on every result is weight paid on every call
- **still absent when the history is whole**, and the absence test now covers three tools rather
  than one — a field that is always present is a field nobody notices

Tests: one loop asserting all six tools carry it, one asserting three carry nothing when the
history is whole.

ADR `2026-08-11-a-hole-in-the-record-is-reported-not-filled` amended; its remaining-risk entry is
⚠️ (old) with a pointer, not deleted.

## [2026-08-11] #189 — a session check that had stopped answering said nothing ✅

#179's own ADR named it: *"Nothing notices a probe that has answered `UNKNOWN` for a week — if
Programmers removes the endpoint the check degrades silently."*

`UNKNOWN` was a DEBUG line and nothing counted how long it had lasted. If the endpoint moves or
starts answering 403: every probe reads `UNKNOWN`, the badge stays quiet **by design**, `/watch`
answers `session: unknown` forever, and the expired-cookie detection built hours earlier is gone
with nothing saying so.

That is the constitution's stated fear, and the project already had the matching rule one layer
down — an unrecognised message type is kept as `Unknown(type, raw)` **and warned about**, because
that is the only way to notice a protocol change. The session check had the `Unknown` half.

`SessionHealth.muteChanged()` reports the transition once in each direction. Three judgements:

- **thirty minutes**, so a laptop losing wifi mid-problem stays silent while a protocol change is
  noticed the same session
- **`EXPIRED` ends the run** — it is the check *working*. Treating any non-`ALIVE` as trouble
  would fire the protocol-change warning at exactly the moment the tool is doing its job
- **no badge state**: the vocabulary was declared full twice, and "we cannot tell whether you are
  recording" is a diagnostic for whoever reads logs

Four tests, all on a returned verdict with a fake clock — including the blip that must stay silent
and the `EXPIRED` that must end the run.

This is the third "announce a persistent state on change" in a day (`BackupReporter`, and the
staleness tick). Two would not be a pattern; three is close enough to say out loud that if a
fourth appears it should be one mechanism rather than three.

## [2026-08-12] #191 — a 200 is not proof of a session, and I claimed it was ✅

Found by reading the project's own §14 (Unverified Items) rather than by a test failing.

`SessionActivityProbe` mapped **200 → ALIVE** on the stated reasoning that "the status is the
whole signal; the body is deliberately not parsed". That holds only if a 200 means what it says,
and §14 records that for this API family it does not: throttling comes back as **200 with an HTML
error page**, not 429. So a rate limit would read as *"your session is fine"*.

Worse, the ADR I wrote hours earlier claimed the chosen endpoint

> returns JSON in both states, so it also **avoids** the 200-with-HTML throttling shape

**I measured 200-with-JSON and 401-with-JSON. I never triggered a throttle on it.** "Avoids" was
an inference wearing the clothes of a measurement — the exact error
[[concepts/assumption-vs-measurement]] is about, committed inside the ADR that corrects an earlier
instance of it. That is the fourth time today an observation was real and the *quantifier* was
invented.

**Deliberately not measured.** Triggering a rate limit means hammering Programmers to prove a
property we can simply stop claiming (development-rules §9.3). The body is shape-checked instead —
correct whether or not this endpoint throttles that way, and it costs one `startsWith("{")`.

No field is read: once the body is well-formed JSON, the status does answer the question.

Four tests, including the two that would have passed a happy-path suite — a 200 carrying an HTML
error page, and the 401-with-measured-JSON that the shape check must **not** swallow.

Also cleaned: §14's `reject_subscription` entry still trailed "what would serve is unmeasured"
after §15.4 answered it, and now names what was chosen.

## [2026-08-12] #193 — score and rating were parsed, promised, and never written ✅

76 of 76 real records: `score: null`, `rating: null`. Including algorithm submits that passed
18/18.

The data was there all along. `algorithm-pass.jsonl`'s `result_lesson_challenge`:

```json
"userScore": "100.0", "perfectScore": "100.0",
"isNewRating": true, "oldUserRating": 1000, "newUserRating": 1001
```

`SubmitMessage.Result` parsed every field of it, and **`GradingFrameFacts` had no field for any of
it**, so nothing crossed the `protocol → application` boundary. Three places claimed otherwise —
the record's class KDoc, both field KDocs, and `aSubmissionRecord()`, which populates a score and
a rating production had never once produced. #136 (`hintLevel` served as a measurement never
taken) inverted: promised by the schema, never delivered.

The rating is the clearest progress signal the judge gives — the one number that says a solve
moved something — and `review_queue` and `slow_passes` reason about confidence and speed without
it.

**The test found a second, older defect.** I wrote the SQL assertion from
`SubmissionRecord.score`'s KDoc — *"Null for every database grading — the SQL path reports no
score"* — and it failed: `sql-pass.jsonl` carries `userScore`/`perfectScore`, and so does
protocol §6's own measured example. The KDoc had been wrong since it was written and **nothing
caught it, because the field was null for every grading anyway** — a wrong explanation of a right
observation. What SQL genuinely never sends is the per-category `scores` array and the rating
(dev rules §2.2, which says exactly that).

`scores` stays unwired on purpose: §14 lists its two-entry shape for efficiency-test problems as
never triggered, so mapping it would be guessing a shape.

Three tests, all from measured captures — a synthetic frame would have proved the mapper and
missed the missing wire, which is the whole shape of this defect.

Not in scope: exposing them over MCP. The record carries them now; whether `review_queue` should
weigh a rating change is a claim about the learner
([[decisions/2026-08-10-scheduling-is-not-diagnosis]]).

## [2026-08-12] #194 — a failing guard let a push through ✅

Found by doing it. Pushing #193:

```bash
git add -A && ./scripts/guards.sh 2>&1 | tail -2 && git commit ... && git push
```

`| tail -2` takes **tail's** exit status. The guard printed `guards: FAILED` — correctly, for a
`log.md` line with no raw beside it — the `&&` chain saw success, and the push went through.

The deeper hole: `.githooks/pre-push` enforced the **wiki gate only**. `scripts/guards.sh` — seven
checks including *no credentials committed* and *no records committed* — ran when a human or CI
ran it, and nothing else. So the guards read like a pre-push gate and were a pre-merge one.

The hook now runs them first and **fails closed**, unlike the wiki gate below it: that one is
explicitly fail-open with a `Wiki-Skip:` escape because it prevents unconscious omission; these
are absolutes with no trailer.

Two deliberate details: the script's own output is not re-worded (a hook that paraphrases a guard
drifts from it), and absence fails **open** — a checkout missing the script passes with a note,
because "the file is not here" is not a violated rule.

Negative-tested: a branch deliberately breaking §7 printed the guard's message, said `Nothing was
pushed`, and `git ls-remote` confirmed it never reached the remote.

The pattern across four amendments to `guards-must-prove-they-ran` is now plain: **a check is
worth what it is wired to.** §3 was a search that never ran; §5–§7 were checks nothing called
until CI; this was a gate whose caller could discard its verdict.

## [2026-08-12] #197 — five descriptions of a hook that had changed the day before ✅

#196 made `.githooks/pre-push` run the constitution guards and fail closed. Every description of
the hook still said it only checked the wiki — including **CLAUDE.md**, which is the file whose
absolutes the new half enforces. A constitution describing its own enforcement wrongly is worse
than most drift.

Five places: `CLAUDE.md`, `CONTRIBUTING.md` (whose section was titled after only one half),
`README.md` ×2, its Korean twin, and `.claude/hooks/inject-state.sh`.

What the descriptions now carry is the **asymmetry**, because flattening it into "the push gate
checks things" would be accurate and useless:

- the guards **fail closed** — absolutes, no escape hatch
- the wiki gate **fails open** — it prevents an unconscious omission, and `Wiki-Skip:` leaves an
  auditable reason

CONTRIBUTING also warns about the pipe that let a failing guard through (#194), since that section
is titled "you will meet it" and this is the way people will meet it wrong.

No behaviour change. The documentation catching up to yesterday.

## [2026-08-12] #199 — correcting what #195 claimed, and pinning what it actually did ✅

#195's PR body said the score and the rating were "not exposed over MCP here". **Wrong.**
`McpRecordJson.full()` encodes the whole `SubmissionRecord`, so #193 made them visible to a client
the moment it landed — verified rather than assumed: a summary carries `score.user` and
`rating.new`/`rating.changed`, and both vanish when null because the format sets
`explicitNulls = false`.

Worth a test rather than a shrug: they arrived **by inheritance, not by decision** — nothing in
the MCP layer names either field. A field that arrives that way leaves the same way, silently, the
next time `HEAVY` grows or the serializer's shape changes, and the failure would be an AI quietly
reasoning without the clearest progress signal the judge gives.

Two tests: present when the grading reported them, both keys **absent** when it did not.

## [2026-08-12] #201 — the guard knew the cookie's shape and not the token's ✅

Found by nearly stepping on it. Registering this server as an MCP tool needs the `/watch` token
in a client config, and the conventional home is `.mcp.json` at the project root — **which is
committed**. Guard §2 greps for `_session_production=…` and nothing else, so a 64-hex token
pasted there would have gone up with every gate green. Neither `.mcp.json` nor
`.claude/settings.local.json` is gitignored.

Both credentials live in `.ps/`, CLAUDE.md forbids both in the same sentence, and only one had a
guard.

**Not a `[0-9a-f]{64}` rule.** Nothing tracked matches one today, so it would pass now and fire
the first time someone adds a `distributionSha256Sum` — and a guard that flags a legitimate hash
gets muted. Two precise checks instead: the **literal value** when this machine has the file
(zero false positives, finds it however reformatted), and the **header with a non-placeholder
value**, always. The documented `"<paste from .ps/watch-token>"` stays passing because of the
angle brackets, the same trick §2 uses for `fake-value-for-tests`.

The value half cannot run in CI and **says which half ran** rather than claiming a check it did
not perform.

Negative-tested both ways plus a canary that the documented placeholder still passes — a rule
that rejects its own documentation is one someone deletes.

The token itself was registered outside the repository, in `~/.claude.json` local scope, and
`git status` stayed clean throughout.

## [2026-08-12] #203 — stats said it twice, and only a real client showed it ✅

The MCP server was registered as a tool in this session (`~/.claude.json`, local scope — the
token stays out of the repository, which #201 now guards). Six tools loaded, three called live:
`stats`, `slow_passes`, `list_problems`. All three carried `incompleteHistory` — **the consumer
that field was written for is this session, and it is visible.**

**The first thing reading the descriptions found was a defect.** #187 appended a shared
`incompleteHistory` sentence to every tool and left the `stats`-specific one #169 had added, so
`stats` says the same thing twice. The other five say it once.

The `curl` verification an hour earlier missed it: it printed the **last 180 characters** of one
tool's description, and the duplication is mid-paragraph in a different tool's. `tools/list`
returns ~1.5 KB of prose per tool and nobody reads that from a terminal — a client puts all six
in front of a reader at once.

That is the half `curl` could not do, and it found something on the first read.

A test now pins that no description contains the sentence twice. The mechanism will recur: append
a shared suffix, forget the bespoke one. The next tool to earn a note of its own is the next
chance to make the same mistake.

Also observed live, unprompted: the session-start hook fired **this repository's** wiki reminder
(`docs/llm-wiki/raw/inbox … 1개`), which is #166 working — the repo-local inbox exists and points
here rather than at the central wiki.

## [2026-08-12] E2E — the whole loop, driven end to end, and what it proved ✅

The MCP server was registered as a tool in this session, so browser → server → MCP is now one
loop I can run alone. Solved 120802 in **python3** (already passed in java, so no account impact)
and read it back over MCP.

| claim | had been | now |
|---|---|---|
| **#193** score and rating reach a record | tests only; 78 real records all null | `score 100.0/100.0`, `rating 1378→1378 changed=false` — **first real data** |
| **#174** a pass belongs to its language | tests only | `slow_passes` lists 120802 **twice**, java and python3. The old code kept the latest only, so the java reading would have vanished |
| per-language files | argued from the layout | `attempts/005.py` beside `004.java` |
| attempt numbering | argued in #174's correction | java 1–4, python3 **5**. Monotonic across languages, no collision — exactly as the correction said |
| sensor | landing | `focusedSec: 37`, counted while the browser was driven |
| MCP round trip | curl only | `get_problem` and `slow_passes` answered, both carrying `incompleteHistory` |

## [2026-08-12] #205 — elapsedSec reads as effort and is wall clock ✅

The E2E record is the evidence: `elapsedSec: 77251` (21.5 hours) beside `focusedSec: 37`, for a
problem that took half a minute on a tab left open overnight.

**Nothing anywhere said which one it is.** `elapsedSec` was the only non-null field in
`SubmissionRecord` with no KDoc, and it appears in zero tool descriptions and zero lines of
`docs/mcp.md`. An AI reading a five-digit *elapsed seconds* concludes the learner struggled — a
confident wrong conclusion from a correctly recorded value, which is the failure this project is
organised against.

And where it *was* described, `ProblemTimer`'s KDoc oversold it: *"the question a record answers
is 'how long did this problem take'"*. 77251 beside 37 does not support that. Same shape as
#193's SQL score KDoc — a wrong explanation of a right observation, surviving because nothing
contradicted it.

Not renamed: the value is correct and a rename is a schema change for existing records. Said
instead, in the three places a reader meets it — the field, the two tool descriptions that return
whole records, and `docs/mcp.md` with the measured pair as the example.

A test pins that **only** `submissions` and `get_problem` carry the explanation: the tools that
return counts or a schedule never show the field, and repeating it there is weight for nothing.

## [2026-08-12] #209 — the JS execution proof rested on the runner image's luck ✅

`gates (windows-latest)` failed on #208 with `node is not installed`. The same failure had hit
#174 hours earlier; that one was rerun and passed, and the report said **if it happens again,
pin node.** It happened again.

The guard was right both times. `JavascriptRunnerExecutionTest` skips politely when node is
absent — correct on a contributor's machine — and the CI step turns a skip into a failure,
because a runner image quietly dropping a toolchain must not silently un-earn a language's
*supported* status ([[decisions/2026-08-07-server-generated-runners]]: support is earned by
executing).

What was wrong is that the proof rested on `windows-latest` **happening** to ship node. Java is
pinned by `setup-java`; JavaScript was the one relying on luck. `actions/setup-node` pins it, so
the proof depends on our declaration.

Rerunning would have been the wrong fix twice over: it trains us to rerun on a guard failure,
which is how a guard stops meaning anything.

Also noted for myself: the owner had to point out that I created #208 and reported without
watching CI to completion. The same thing happened on 2026-08-05 (*"너 ci 모니터링 재대로 못하는거
같은데??"*). The loop is issue → branch → PR → **watch → fix or merge**, and stopping at PR
creation leaves the branch in someone else's lap.


## [2026-08-12] #211 — the repeatability job threw away the evidence it exists to collect ✅

`repeatability (no cache)` runs the suite three times from clean, to catch what fails only
sometimes. It caught something on #210's own run:

```
RecordWriterGitTest > a pass pushes, and the push carries another problem's pending commits with it()
    org.junit.platform.commons.JUnitException at ArrayList.java:1604
        Caused by: java.io.IOException at ForEachOps.java:186
```

That is all of it. **The job uploaded nothing**, so there is no report and the stack is whatever
the log chose to print. `gates` has always uploaded; the one job whose failures are hardest to
reproduce did not — and [[concepts/assumption-vs-measurement]] already records why that matters:
*"The job log truncates stack traces; the uploaded report does not."*

**The flake is deliberately not fixed.** `ForEachOps` inside a `JUnitException` is the shape of a
directory walk racing something — plausibly `@TempDir` cleanup against a `git` subprocess the
test's real remote left behind. That is a hypothesis, and a fix without a reproduction is
guess-based debugging, which the constitution forbids by name. Local three-times-from-clean
passed, and `gates (ubuntu-latest)` passed in the same run, so it is not deterministic.

The next occurrence arrives with a full stack instead of a line number.

## [2026-08-12] #207 — sincePrevSec was declared, fixtured, documented, and never once set ✅

Third of this shape in two days. 80 of 80 records null, **including a fifth attempt**, while:

- design §5.2 shows `"sincePrevSec": 312,  // since the previous submission`
- the KDoc explained the null as *"Null for the first submission of a problem"*
- `aSubmissionRecord()` populated `312`
- `src/main` contained the declaration and nothing else — no writer, no reader

So the KDoc gave a cause that cannot be true for a fifth attempt, and every test saw a number
production had never produced. Same as #193's `score` KDoc: a wrong explanation of a right
observation, surviving because nothing could contradict it.

**Wired rather than deleted.** The gap between attempts separates five submits in ninety seconds
from five across three evenings — guessing from thinking — and `review_queue` reasons about
confidence from the attempt count and the questions tab without it. **Reported, never scored**:
this adds a fact to an item, not a term to a formula
([[decisions/2026-08-10-scheduling-is-not-diagnosis]]).

`SubmissionGaps` mirrors `AttemptAuthority` exactly — restored from the submission log at startup,
advanced in memory, consulted inside the confined writer section. The log stays the one authority
for both numbers.

The definition is a choice and is stated as one: **any action, any language.** The log holds runs,
and "five runs in two minutes" is the same signal; and unlike #174's scheduling rule — which asks
whether a *pass* demonstrates a language — this asks how long since the learner last touched the
problem, where switching language is still touching it.

`RecordedSubmission` gained `ts`, leniently: a line whose timestamp will not parse keeps every
other field and restores no gap, because the attempt counter matters more than a gap.

Nine calculator tests plus three through the real writer, including one that a restart must not
turn back into a first attempt. **Verified live**: a run driven in the browser recorded
`sincePrev 2034` — 34 minutes since the previous grading, correct to the second.

Browser note worth keeping: clicking these buttons **by `ref` shows the tooltip and does not
click**; only coordinate clicks fire them. Cost two false starts today.

## [2026-08-12] #212 — six of seven languages were classified by patterns written for two others ✅

Found by counting two lists that should have matched: `FileDerivedArtifacts.GENERATORS` has
seven languages, `VerdictResolver.compilerDiagnostics` had two patterns, and nothing recorded
which languages that left uncovered. Same source as #191/#193/#205/#207 — the project's own
record of what it does not know, this time a KDoc that had stated its gap and left it unowned:

> Not here on purpose: `IndentationError` and `TabError` … One line each when they are.

**The measurement contradicted the diagnosis.** The issue predicted four uncovered languages;
the wire said one. C, C++, Kotlin and JavaScript were already correct **by coincidence** —
clang and kotlinc add a column (`:8:15: error:`) that the javac pattern matches on its tail,
and node prints `SyntaxError:`, which the python pattern catches. Six languages were being
served by two patterns naming two, and tightening either would have broken three with no test
naming any of them.

The genuine misses, all confirmed live in the record before the fix:

- `csharp` — `/Solution0.cs(10,31): error CS1002:` brackets its position, no colon-digit-colon
- `python3` `IndentationError` / `TabError` — SyntaxError subclasses printing their own names

**E2E, as the owner asked: all seven languages, one broken run and one correct submit each**
on lesson 181952. Every wire language string matches its generator key and all eight runner
files were written — assumed since #37, measured now. A mismatch would produce no runner and
no message. Protocol §7.2 (shapes), §15.5 (the sweep), nine new whole fixtures.

Three things fell out that were not the point:

- **javac sends every diagnostic in one frame**, ending `2 errors` — so the earlier ADR's "one
  error frame per compiler diagnostic" is wrong, and `algorithm-run-error.jsonl`'s two frames
  are of different provenance rather than one session. Said in the fixture README rather than
  quietly corrected.
- **A capture-key collision on an algorithm problem.** `a-grading-is-its-whole-session` calls
  that vanishingly unlikely outside SQL because timings jitter; a failure that never reaches a
  testcase has no timings either. No behaviour change (the live path stopped consulting the
  index at #159), but the quantifier was wrong.
- Programmers rejects Kotlin's no-arg `fun main()` with a message about a missing main method
  — **identical for correct and broken bodies**, which confounded two readings until the
  editor template was checked. One extra run caught it; a negative observed through your own
  setup is not an observation.

**A wrong reading I announced before checking**: "every record is written twice, every `stats`
count is doubled". It is the append-only code-attachment correction (`codePending: true →
false`), which `RecordHistory` collapses. Cause: counting capture keys and reading a `tail -2`
dump whose second record was truncated before the field that would have shown it.

Browser mechanics worth not rediscovering: the editor is CodeMirror 5 (`.CodeMirror.setValue`
beats typing), `?language=<name>` switches without the dropdown, and **a click in the same
`browser_batch` as a `navigate` does not register** — one wasted round trip per language until
that was clear.

## [2026-08-12] #215 — a code reset was counted as a hole in the grading record ✅

Found while verifying the #212 sweep through MCP, not by looking for it: `stats` came back
with `incompleteHistory.lessons: [120802, 181946, 181952]`, and 181952 was new. The orphaned
frame was mine, from pressing 초기화 to read the Kotlin template:

```json
{"action":"reset","initialCodes":{…},"msg":"코드를 초기화하였습니다."}
```

It carries a `msg`, so `frame.facts` is non-null, so it took the orphan path. Nothing was lost.
The log even said *"a grading frame (action=null)"* — it is not a grading frame, and the wire
said `reset`, not null.

**Why it is worth a PR.** `incompleteHistory` is a trust signal:
[[decisions/2026-08-11-a-hole-in-the-record-is-reported-not-filled]] puts it on every tool
answer so a reader distrusts a diagnosis drawn over a history with holes, and its own argument
is that *presence is the signal*. A warning that fires on an ordinary editor action spends
that credibility on nothing — and it fires for any user, not just this one.

The distinction the code lacked: **recognised-and-not-a-grading** vs **unrecognised**. Both
arrive as `action = null`, and only the second deserves an alarm. `GradingFrameFacts` gained
`outsideGrading`; §8's `save` and `reset` set it; everything else counts and warns as before.
A rename on their side stops matching and falls back to the loud path, which is the direction
this must fail in.

Two smaller calls, both stated in the code:

- **Not filed under the raw orphan directory.** That directory means "frames of a grading no
  record represents" and is where a person looks for lost work. DEBUG instead.
- **`save` is included without a capture.** §8's catalogue is bundle-extracted, not our wire.
  The failure is asymmetric — an entry that never arrives costs nothing — but it is an
  inference beside a measurement and the comment labels it as one.

Also learned: a reset frame **has no `type` field at all**, which the §8 catalogue's type list
does not lead you to expect, and it ships the starter code back in `initialCodes` keyed by the
codes key. Protocol §8 updated.

**TDD note against myself.** My first red/green check stashed *everything* — including the new
test — so both runs were silent and proved nothing. Redone by reverting only the four
production files: 1 failed, then BUILD SUCCESSFUL.

## [2026-08-12] #218 — the whole pipeline verified against an empty record repository ✅

Owner's call, and the right one: *"앞선 수많은 테스트를 통해 record repo에 잘못된 데이터들이 너무
많을거같은데 한번 싹 삭제하고 아예 처음부터 다 다시 e2e 현재 상태 기준으로 build 다시해서."*

The 57 records had been written by **at least five code vintages** — 49 null `score` (#193
fills it), 40 null `sincePrevSec` (#207), three verdicts #212 classifies differently, two
orphaned gradings. As evidence that the *current* pipeline works, that is nearly worthless: a
defect fixed a week ago is indistinguishable from one still present.

**Archived, not deleted.** Committed and tagged `archive/2026-08-12-pre-clean-slate`, pushed,
before anything was removed. Credentials were never at risk: the session cookie and `/watch`
token live in the project's own `.ps/`, not the record repository.

### Result — 15 records, one build (`b3d68cc`)

| | |
|---|---|
| compile failures classified | **7 / 7**, including csharp and python indentation |
| passes with `score` and `rating` | 7 / 7 |
| verdicts unresolved | 0 |
| `sincePrevSec` | 14 / 15 (the first grading has no predecessor) |
| attempt sequence | 0 → 8, monotonic across seven languages |
| runner files | 8 |
| orphaned frames | **0** — `incompleteHistory` absent from every MCP answer |
| WRONG verdict | exercised for the first time end to end (`score: 0.0/100.0`) |

`review_queue` correctly returns nothing: every pass is minutes old and the first interval is
60 days. An empty answer from a tool that only reports what is *due*.

### What the clean slate exposed

- **`attempt 0`** on the first record. Not a defect — `AttemptAuthority.NONE = 0` means "a run
  before the first submit belongs to no attempt file", which only becomes visible on an empty
  log. Checked before reporting.
- **#217 — every language switch warns that broadcasts may have been lost.** Seven switches,
  seven `WARN … JobCancellationException … anything broadcast meanwhile is lost`. The
  cancellation is *ours*, issued to move to the next language's channel — the ordinary path
  #174 exists for. Same shape as #215: a warning that fires on an ordinary action is a warning
  that gets trained away, and it shares a log with reconnect warnings that mean something.

### Browser mechanics, finally pinned

Three sessions of flaky clicks had one cause, and neither of my two earlier theories was it.
**Click coordinates resolve against the last screenshot.** A batch that navigates and then
clicks is aiming at the previous page's frame, so the click misses; adding a `hover` helps only
because it forces a fresh frame. The reliable shape is:

```
[navigate, wait, screenshot] → [setValue, hover, click, wait, screenshot]
```

Every batch ends with a screenshot so the next one has coordinates. I twice concluded "capture
is broken" from a missing record without checking the screen — the same negative-through-my-own-
setup error the wiki already names.

## [2026-08-12] #217 — unsubscribing was reported as a dropped connection ✅

Found by reading logs during the clean-slate sweep, which was about something else. Seven
language switches produced seven identical warnings; **the repetition is what made them
visible.** One-off noise stays invisible.

`runCatching` in `collectOnce` caught the `CancellationException` that our own `unsubscribe`
raises. Three consequences from one line, and the loud one is not the dangerous one:

- a WARN per switch about broadcasts that were never at risk, in the same log as the reconnect
  warnings that mean something — the #215 shape again, one day later
- an `UNREACHABLE` written back into the health map after `unsubscribe` removed it
- **one more pass of the retry loop**, calling `connectionLost()` and settling any grading still
  in flight as INCOMPLETE, logged as *dropped mid-grading*

**The textbook fix was wrong and the suite caught it.** `if (it is CancellationException) throw
it` turned `silence beyond the deadline ends the attempt and reconnects` red immediately:
`Flow.timeout()` reports the deadline by throwing `TimeoutCancellationException`, which is a
`CancellationException`, so rethrowing on type disables the reconnect this class exists for
(protocol §11 — a socket measured closing silently after ~30 minutes).

The discriminator is `!currentCoroutineContext().isActive` — a timeout leaves the job active,
`unsubscribe` does not. That test predates this work by weeks and is the reason the near-miss
cost one test run instead of a month of silent gaps.

Also corrected in passing: I wrote *"the one deliberate sleep in this file"* in the new test's
KDoc, and the heartbeat test already had one. Exactly the confident-but-false comment
[[concepts/tests-that-explain-defects]] is about, written while adding a test for a defect of
the same family.

## [2026-08-12] #214 — stats collapses the languages and did not say so ✅

The last accepted cost left standing from #174: `review_queue` and `slow_passes` key on
(problem, language), `stats(groupBy=problem)` counts per problem. The ADR ended *"it is a thing
a reader has to notice"* and stopped there.

The clean-slate sweep made it concrete for the first time — lesson 181952 now has a pass in all
seven languages, so `review_queue` holds seven items for the one bucket `stats` reports. Both
answers are right; read side by side without the explanation they look like a disagreement.

**No fourth `groupBy`.** `groupBy=language` already answers the other axis, and a
`problem_language` group would be a third way to ask what two calls answer. What was missing was
disclosure, not a feature — so the `stats` description says which axis it collapses and names
the two tools that do not, `docs/mcp.md` and its Korean twin say it in prose, and a test pins it.

Where it goes matters: the description is the only place a client learns it, because `tools/list`
is read once and the results are counts. Same reasoning as #187's move of prose out of answers.

Remaining cost, stated rather than solved: a reader who never reads the description still meets
the difference unexplained.

## [2026-08-12] #222 — a run that ran too long was recorded as a crash ✅

Found by going after the one verdict the clean baseline was missing. It was missing because
nothing had ever driven it, and driving it broke immediately.

**The run path has its own time limit and its own sentence for it**, and only the submit's was
ever written down:

| path | limit | message |
|---|---|---|
| submit | ~87 s measured | `실패 (시간 초과)` on the failing testcase |
| run | **10 s** | a sentence about exceeding the run time, on an `error` frame |

They share no words. The submit pattern matched nothing, so the run fell through to the
compiler-shape branch, found none, and landed as **RUNTIME_ERROR** — the learner's code was
slow, and the record said it had crashed. Those ask for opposite next moves, which is the whole
reason for keeping five verdicts apart.

Same family as #212's C#: a measurement taken on one path, generalised to a path nobody had
measured. Kept as a **second** pattern rather than a loosened first one — two limits on two
paths, and one regex covering both would stop saying which was seen.

**What did not reproduce, and was therefore not fixed.** The first timeout run left three
orphaned frames: its `start` never reached us and only `error · error · result` arrived. The
second run, driven identically with the log tailing, recorded normally. The frames were kept,
which is the designed behaviour, and a fix without a reproduction is the thing the constitution
forbids by name.

Also verified on the way, and the reason this problem was chosen: **the `solution(...)` problem
shape**, untouched by the clean sweep, which is all `main`+stdin. The runner generator parsed
the signature and emitted `new Solution().solution(2, 3)` against the judge's own examples —
the path that matters most and had never been exercised on this build. RUNTIME_ERROR came from
the same problem (`ArrayIndexOutOfBoundsException`), so four of five verdicts are now measured
against `b3d68cc`, and the fifth is this fix.

## [2026-08-12] #225 — slow_passes ranks runtime startup, and said so about the wrong thing ✅

Found by calling the two MCP tools the clean sweep had never called. `slow_passes` over lesson
181952 — one statement of code in every language:

```
java 83.33ms · kotlin 65.19 · javascript 36.28 · csharp 22.42 · python3 10.74 · c 1.17 · cpp 1.12
```

**74×, and none of it is the code.** The two problems whose solutions actually compute sit at
the bottom at 0.02–0.04 ms. So the top of a list titled "your slowest passes" is "which runtime
starts slowest".

The caveat existed and explained something else — that no baseline is applied *because there are
not enough peers*. True, and not the trap a reader meets first. Same shape as #205's
`elapsedSec`: a number whose name promises one thing and whose value measures another,
disclosed in a sentence that does not prepare you for the size of it.

**Quantified rather than featured.** The measurement goes into the tool description and
`docs/mcp.md`, `language` was already on every item, and no filter or grouping was added.

Noted while checking, not a defect: `submissions(verdict=TIMEOUT)` returns nothing though a run
did time out today — it was recorded before #223 landed and the log is append-only. Records are
not rewritten when a classifier improves.

## [2026-08-12] #227 — the design still promised the dashboards #96 had removed ✅

Found by auditing the design's deliverables against the tree, after the owner asked whether the
server could generate an Obsidian index. The audit's result was mostly *"already handled"* —
and the one hole is the one they had noticed.

`RecordRepositoryTemplateTest` records the earlier half:

> It drifted exactly as an unchecked document does (#96): it promised five Dataview dashboard
> notes nothing generates … **A reader followed those into an empty vault.**

The template was fixed and pinned by a test. **The design section those sentences came from was
not**, and `CLAUDE.md` points at it for design decisions. So it misled the next reader, who was
me: I read §5.5 an hour ago and answered that the five notes are specified and unbuilt, when
they had been taken out of the user-facing copy deliberately.

**The five are not one backlog item**, which is the part worth writing down rather than
deleting:

- `_dashboard.md`, `_review.md`, `_warmup.md` — aggregation, allowed, unbuilt, no owner
- `_weakness.md` — **interpretation, and CLAUDE.md forbids rule-based analyzers in the server.**
  The prohibition postdates the design and has never been reconciled with it in writing, the way
  `review_queue`'s boundary was settled by `2026-08-10-scheduling-is-not-diagnosis`

Amended rather than deleted, in the doc's own `⚠️` convention: the design is a record of what
was decided on 2026-08-04, and what it got wrong is evidence. Also corrected there:
`SolutionTest.java` (the server writes `RunnerTest.java`) and `meta.json` (never existed).

Nothing was generated. Building the allowed three is a decision with no owner, and `_weakness.md`
needs an ADR before it can be one.

**The sweep was widened rather than stopped**: every filename the design names, checked against
both repositories. Two more surfaced and they are not alike. `.ps/concept-graph.json` is labelled
**(P2)** and is honest about being future work. `.ps/pg-metrics.jsonl` sits in the architecture
section with no marker, reading as behaviour, and nothing polls its two endpoints — amended in
the same style, and named as a decision rather than a chore, because wanting it back means two
daily requests Programmers did not ask for. `hints.json` needed nothing: the design already
corrects itself sixty lines later.

## [2026-08-12] #229 — a tag map, so the graph can show what was never met ✅

Designed in conversation with the owner, who asked whether the vault could show which problem
types they had covered. The answer needed a fact first: **a graph of your own records cannot
show a type you never met** — it is absent, not faint.

So the server writes one note per catalogued tag, including the tags no record touches. That
note is the isolated node, and the isolation is the finding.

**The denominator is what keeps it honest.** The catalog uses 83 tags across 689 problems, 37 of
them carrying two problems or fewer while `implementation` carries 379. Without `catalogTotal`,
an isolated `tsp` (one problem anywhere) and an isolated `dp` (38) look identical and only one is
a gap.

**It settled a standing conflict.** Design §5.5 listed a server-generated `_weakness.md`;
CLAUDE.md forbids rule-based analyzers in the server. The line turned out to sit at that
section's own example `slowFlag` — the server deciding what counts as slow — not at the
aggregation. The server counts and never names: no ratio, no ranking, no threshold, no "start
here". `_weakness.md` is closed rather than deferred
([[decisions/2026-08-12-the-server-counts-and-names-nothing]]).

Verified live on build `133be49`: 83 notes written, `dp: 0/38` and `arithmetic: 2/48` both
correct against the day's records, the server committed them itself, and a restart produced no
churn — identical bytes, as designed.

### What the plan got wrong, and one thing I did

Three corrections, all of them the plan's own text rather than the design:

- ktlint requires a parameter list on one line where it fits, so the signature shrank — and the
  renamed parameters then broke the test's named arguments. The plan had specified two different
  names for the same function.
- The guard test extracts `tags/<tag>.md` from the template block, not `tags/`. The pattern is
  what the server writes, so the set was corrected rather than the template.
- **I wrote Task 5's implementation before its test.** Noticed because the test was green on the
  first run, which proves nothing. Verified properly by removing the wiring and watching two of
  the three go red, then restoring.

### Orca, honestly

The owner asked for orchestration. Two workers were dispatched and **neither started.** The
first had its prompt mangled — a multi-line spec split by the TUI composer and never submitted,
which was my error. The second received the TASK block intact and still produced nothing; I do
not know why and did not guess. Both tasks were closed as failed, both workers stopped, and the
work was done inline at the owner's instruction.

## [2026-08-13] #231 — the map needed edges between tags, not only into them ✅

#229 shipped and the owner opened the vault: **81 of 83 tags isolated.** A dust cloud.

The decision's own argument was that an isolated node is the finding — and it holds only **when
isolation is rare.** With four problems recorded, near everything was isolated and isolation
carried nothing. Nothing in the reasoning was false; it assumed a vault with history and never
said so.

**Measured before writing code**, which is what made the fix small and threshold-free:

| | |
|---|---|
| co-occurring pairs | 255 over 83 tags |
| tags co-occurring with nothing | **0** |
| highest degree | `implementation` 27, not 82 |
| average degree | ~6 |

So every tag has a neighbour, the map has shape before anything is solved, and the hairball this
might have produced does not exist. **No threshold** — the numbers say none is needed, and a
cutoff would be a judgement nothing asked for. Links are alphabetical, because ordering them by
strength is a claim of its own.

Prerequisite ordering stays unbuilt: design §6.10's concept graph orders *learning*, which is
judgement, and inventing our own taxonomy is forbidden outright. Co-occurrence is different in
kind — it counts what solved.ac already tagged.

**Shown before it was built.** A self-contained preview drawn from the real catalog let the owner
compare both states and say yes before any code existed. Its first version oscillated violently:
the centering force was scaled by viewport size (`min(W,H) * 0.00042` ≈ 0.34), so a node 300px
off-centre moved 100px per frame and diverged, and there was no alpha decay to settle it. A
constant and a cooling schedule fixed it. Worth remembering that a preview has its own bugs and
they are not the design's.

## [2026-08-13] #233 — 43 of 83 tags were unlinkable, and a test had pinned it ✅

Found while waiting on #232's CI, by asking a question the tests never did: **does an emitted
link name a file that exists?**

`RecordLayout.slugOf` turns `binary_search` into `binary-search.md`, and both writers built the
link from the *tag*. **43 of the catalog's 83 tags carry an underscore**, so a link to any of
them named a file that does not exist and Obsidian would draw a ghost node instead of an edge —
in a feature whose entire purpose is the edges.

**Measured, so the blast radius is stated rather than implied.** The live vault holds 4 links
today, to `arithmetic` and `implementation`; neither is slugged, so **nothing on disk was broken
yet**. #232 emits 510 tag→tag links over 255 pairs, of which **178 (35%) would have dangled** —
so the defect shipped latent in #229 and would have become visible the moment the edges did.

**The test that should have caught it asserted the defect instead:**

> `a tag the filesystem would not take keeps its spelling in the field`

That is correct about the frontmatter — the `tag:` field is the datum and must stay verbatim —
and it stopped there. It never asked what a *link* needs. The spec made the same distinction and
stopped at the same place, so code, test and spec all agreed with each other and none of them
with the filesystem.

Exactly `concepts/tests-that-explain-defects`, which I had cited twice today before writing it.

**The fix is the missing check, not a better rule.** `RecordLayout.tagNoteLink` owns the answer,
both writers ask it, and the new test resolves every emitted link against the files actually
written — asserted against the directory rather than against the naming rule, because a test
that restates the rule agrees with whatever the rule currently is.

## [2026-08-13] #232 merged, and the map verified against the running server ✅

`b40bb71`. Rebuilt (`docker compose up -d --build`), restarted, then asked the vault the
questions a test cannot:

| | |
|---|---|
| tag notes on disk | **83** |
| wikilinks | **514** — 510 tag→tag over 255 pairs, 4 problem→tag |
| dangling links | **0** |
| notes disagreeing with the records | **0 of 83** |

The last row is the strongest: the counts were recomputed straight from `log/submissions.jsonl`
and `catalog.json` in Python — an independent implementation of `TagCoverage` — and diffed
against what Kotlin wrote. None disagreed. The 514 also matched the count predicted before the
deploy, so the arithmetic was done first and the world agreed after.

`tags/binary-search.md` keeps `tag: binary_search` in the field and is linked as
`[[tags/binary-search]]` from elsewhere, which is #233 fixed where it can be seen.

**Still open: #16 — the owner opens the vault.** Every check above is mine; the graph rendering
is not something this side can see.

## [2026-08-13] #235 — `stats` counted runs as submissions ✅

Found by asking two of the server's own tools the same question:

```
list_problems(status=passed)  →  181952 … "attempts": 8
stats(groupBy=problem)        →  181952 … "count":   15
```

`stats(groupBy=verdict)` reported 7 COMPILE_ERROR and 2 RUNTIME_ERROR. Split by action, **every
one of them is a run**: 11 submissions, 10 passes, 1 wrong. An AI reading that tally would have
described a learner who cannot get code to compile, and the MCP layer exists precisely so the AI
interprets and the server does not.

**The rule existed in four places and was missing from the fifth.** `ReviewQueue`, `SlowPasses`,
the tag map and `CatalogBrowse` all test `action == SUBMIT`; `SubmissionTally` counted whatever
`RecordQuery.history()` handed it. Its own KDoc said "counts submissions", `docs/mcp.md` said the
same, and design §5.1's *a run is not an attempt* had been obeyed by `ProblemReadme` since it was
written.

**No test pinned it either way** — every tally test used the fixture's default action, so the
calculator was never handed a run. The whole suite stayed green after the fix, which says the
same thing from the other side. A rule kept by convention at four call sites is enforced at none.

## [2026-08-13] #234 filed, deliberately not fixed

`reconcile()` is `git add --all`, so it commits whatever is in the vault. Four commits in the
record repository carry Obsidian's editor state and one — the 23:00 backup — contains **nothing
else**, under a message that says it reconciled records.

Left for the owner because the options differ in risk, not in taste: scoping the staging to what
the server writes trades a visible annoyance for a record going uncommitted, which is the
direction the constitution cares most about. Recommendation in the issue is to ignore
`.obsidian/` instead, and that has costs of its own.

## [2026-08-13] Verified in passing — the daily backup fires on its own

`14:00:29Z` = **23:00 KST**, exactly `TRACKER_BACKUP_AT`, first time observed unattended:
reconciled, committed, pushed, and logged that it had. A scheduled job is another thing no unit
test can prove.

Also checked and **not** a defect: the startup warning about lesson 120802's 3 orphaned frames
repeats on every boot, but nothing is re-processed or rewritten — `unprocessed()` walks only the
direct children of `.ps/raw/`, and `orphans/` is a standing evidence directory whose file has not
been touched since 2026-08-12 15:41. It reports a condition that is still true.

## [2026-08-13] #237 — `get_problem` counted runs too, one code path over ✅

Found by continuing the same comparison that found #235. Same server, same log, same problem:

```
list_problems(status=passed)  →  181952 … "attempts": 8
get_problem(181952)           →  181952 … "submissionCount": 15
problems/181952-…/README.md   →  attempts: 8 · runCount: 7
```

`McpRecordJson` published the array's length, and the array holds runs — which is right, because
a run is where the compiler output comes from and `get_problem` is the tool that returns it. Only
the count was wrong.

**Answers both now**, in the words `ProblemReadme` has used since it was written: `submissionCount`
and `runCount`. Two views of one problem should not need two vocabularies.

**And the rule is written once.** `SubmissionRecord.isSubmission()` — a behaviour method beside
`isJudged()` and `isCodeAttached()` — now carries it, and `SubmissionTally` asks it too. The
comparison had been missing twice in two days, which is what a rule spelled out at each call site
does eventually.

`ReviewQueue.passed()` and `SlowPasses.passed()` were left alone: they combine the action with a
verdict, they are correct, and rewriting correct code was not this issue.

### The sweep that found it

Every read surface asked the same question and checked against the records:

| view | agrees with the records |
|---|---|
| `list_problems` | ✅ |
| `slow_passes` | ✅ 9 timed + 1 untimed = the 10 (problem, language) passes on record |
| `review_queue` | ✅ 0 due — nothing has aged past the 3-day band yet |
| `tags/*.md` | ✅ 0 of 83 disagree |
| `problems/*/README.md` | ✅ attempts 8, runCount 7 |
| `stats` | ❌ #235 |
| `get_problem` | ❌ #237 |

Two wrong out of seven, both the same mistake, neither visible from inside its own tests.

### Checked and judged not worth changing

The container healthcheck is `curl -s -o /dev/null http://127.0.0.1:8080/`, and `/` answers
**404**. Without `-f`, curl exits 0 on any HTTP response, so the check proves the port is
listening and nothing more. For a Spring Boot app that is nearly the same statement — a context
that fails to start does not serve at all — and the alternative is a new unauthenticated
endpoint. Left as is; say so if you disagree.

The MCP token gate was re-verified after the rebuild: no token → 401, wrong token → 401.

## [2026-08-13] #239 — a runner needs a run, and the page did not say so ✅

`template/ps-records/README.md` gave language as the only reason a runner can be absent. The
record repository disagrees:

```
problems/120802-두-수의-합-구하기/   examples.json  RunnerTest.java  runner_test.py  …
problems/120804-두-수의-곱-구하기/   Solution.java   ← neither
```

Both Lv0, both java, both passed. 120802 had 3 runs; **120804 had none** — one submit, first try.
Examples are announced by the grading stream (protocol §7) and only a run announces them, which
`RecordWriter` states where it calls the store: *the store is a no-op for a grading that
announced none, so a submit cannot blank what its preceding run wrote.*

Nothing was broken — the refusal is designed and logged. The page was incomplete, and the gap is
invisible until someone passes first try and wonders where their runner went. One sentence, in
both twins.

Not fixed in code: building examples from the problem page would add a protocol dependency for a
file that regenerates the moment the user presses Run once.

## [2026-08-13] Correspondence checks that came back clean

Two directions of the same question — does what a record names exist, and does what exists have
a record?

| check | result |
|---|---|
| `rawPath` · `codePath` named by records | **33 paths, 0 missing** |
| attempt files on disk no record names | **0** |
| `sensor.focusedSec` | non-zero on 4 records (32 s, 37 s), 0 on the 18 from the automated E2E run — the field is alive, and 0 there is the correct reading of a tab that never had focus |

The record repository and the filesystem agree exactly, in both directions.

## [2026-08-13] #241 — the tag↔tag edges come back out, and the tags name their problems ✅

Owner opened the graph and asked two things I had not: **why is node size the catalog, and why
can I not click a type and reach the problems I did.** Both were right.

Obsidian sizes a node by how many notes link to it and cannot read frontmatter, so on the live
vault:

| tag | links | of which are the owner's problems |
|---|---|---|
| `implementation` | 29 | **2 (7%)** |
| `arithmetic` | 7 | **2 (29%)** |

`implementation` would need **27+ solved problems** before the owner's own work matched the
catalog's contribution to its own node. Until then the biggest node on the map is the catalog's,
identically for every user.

**#231 was solving the wrong problem.** The dust cloud was not a defect — it was an accurate
picture of four solved problems. 510 catalog edges made the map *look* informative while removing
both signals it existed for. And colour does the "never met" job better than isolation ever did:
`["solved":0]` keeps working at any vault size and costs the map nothing.

Reversed in [[decisions/2026-08-13-node-size-is-what-you-solved]]; the #231 amendment is marked
superseded rather than deleted, and spec §3.3 and §3.4 carry the same mark. §3.4 had refused to
list the problems on the grounds that a generated list is a second copy that can disagree with
the backlink pane — it cannot, because the note is overwritten whole from the records exactly as
`solved: 2` is.

`RecordLayout.problemNoteLink` owns the link for the same reason `tagNoteLink` does (#233), and
the resolution test now resolves **every** emitted link — tag and problem — against files on
disk, with the regex widened to match aliased links.

Colour recipe documented in the template README and its Korean twin, because a new vault is now a
field of isolated dots and the reader has to be told that this is the finding.

**Known and next**: the container runs in UTC, so `ts` is stamped `…Z` and the attempt history
renders `05:23` for a 14:23 KST submit. The instants are correct and the display is nine hours
out. Its own issue, next.

## [2026-08-13] #243 — the server recorded in UTC, so the vault read nine hours off ✅

`problems/181952/README.md` showed `05:23` for a submit made at 14:23 KST, because the record
carried `"ts": "…T05:23:07Z"` and `ProblemReadme` renders at the offset the record carries — which
was UTC, because the container sets no `TZ`.

**The instants were always correct.** Nothing was mis-recorded and nothing needed repairing; the
page showed the right moment on the wrong clock.

Three things changed:

- `TZ` in `compose.yaml` and `.env.example`, defaulting to **UTC** rather than the developer's
  zone. Neutral by default, and the server now logs `Records are stamped in <zone>` at startup so
  an unset clock is visible rather than discovered in a record.
- `TRACKER_BACKUP_ZONE` **no longer defaults to `Asia/Seoul`** — it falls back to the process
  clock. `GitConfiguration`'s own KDoc had said *a developer's timezone must not become
  everyone's* while the default two lines below it did exactly that.
- `ConfiguredZone`, next to `ConfiguredPath` and tested the same way: blank falls back, a present
  value is strict, and a typo throws at startup where it is still cheap.

Locally, `TZ=Asia/Seoul` added to `.env` — the compose default is UTC, so the fix does nothing
here without it.

**Rows written before the change keep `Z`,** so the attempt history steps by nine hours at the
changeover. Rewriting stored records to change a rendering is not a trade this repository makes,
and the step is documented in the template README so it does not read as a capture bug.

**Two places the first pass missed**, found by grepping `Asia/Seoul` across the tree rather than
re-reading the diff:

- `docs/bootstrap.md` said *"The daily backup defaults to `Asia/Seoul`"* — a sentence #243 makes
  false. Rewritten in both twins to describe `TZ` instead.
- `DailyBackup`'s constructor default was `ZoneId.of("Asia/Seoul")`. Unused in production, since
  the composition root always passes one, but it is the same hardcoded zone one layer down and it
  would have applied silently to any future caller. Now `ZoneId.systemDefault()`.

The change set looked complete from inside itself. The grep is what disagreed.

**And then CI caught what neither of those greps could.** Replacing the hardcoded default with
`ZoneId.systemDefault()` turned `DailyBackupTest` red on all four Linux/macOS jobs and green on
this machine, because every instant in that test is written in Seoul terms and it had been taking
the zone from the constructor default. It was asserting the default, not the behaviour.

`DailyBackup.zone` now has **no default at all**. `systemDefault()` would have been worse than the
hardcoded zone: a caller that omits it behaves differently on two machines. An hour means nothing
without a zone, so every caller says which one — the composition root from `TZ`, the tests from
`SEOUL`.

Verified the way the failure asked for: `TZ=UTC ./gradlew test --rerun-tasks` — the whole suite,
under the clock CI actually runs on. Also spot-checked `America/New_York`.

Worth keeping: local runs are Asia/Seoul and CI runners are UTC, so between them a test that
depends on *either* zone gets caught. That only holds while both keep running.

## [2026-08-13] #245 — CI ran the suite on one fork, and recompiled it three times ✅

Wall clock is the slowest job, and that is `repeatability (no cache)` at **5m52s** — the suite
three times, 115s + 99s + 99s.

**The Gradle cache was not the gap.** `setup-gradle@v5` is on every JVM job and the logs show
`Cache hit` for dependencies, transforms, wrapper zips, instrumented jars and the Kotlin DSL.
`--no-build-cache` disables the build cache only, which is the point of that job.

Two things were actually costing time:

| | measured on a 14-core laptop |
|---|---|
| `maxParallelForks` unset → 1 | 30.8s |
| 2 | 19.9s (−35%) |
| 4 | 17.1s (−44%) |
| `--rerun-tasks` → `--rerun` | −10%, and the compile share is bigger on a 2-core runner |

`availableProcessors() / 2` rather than a literal: runners have four cores against this laptop's
fourteen, so the honest expectation is nearer the two-fork number.

**The parallel-safety note I first wrote was wrong, and grep said so.** I claimed every
file-touching test uses a `@TempDir`. Three counterexamples: `StateLocationTest` sets the
JVM-global `user.home`, `McpControllerTest` writes a fixed `build/tmp/` path, and an analyzer test
names `/tmp/ps-records` (only in a message — harmless). None of them break under
`maxParallelForks`, because a fork is a separate JVM running its classes one at a time — but all
of them would break under JUnit's `parallel` execution mode. The comment now says that, so the
next person to reach for more parallelism knows what they are stepping on.

Verified: three repeats under `TZ=UTC` with forks on, all green, plus the four gates. Local
`scripts/test.sh` went 30s → 17s.

**The runner numbers, which were the point** (#246 against #244, same workflow):

| job | before | after | |
|---|---|---|---|
| gates (macos) | 4m15s | **1m37s** | −62% |
| gates (ubuntu) | 4m14s | **2m55s** | −31% |
| coverage report | 1m59s | 1m33s | −22% |
| **repeatability** | **6m2s** | **5m8s** | **−15%** ← the wall |
| gates (windows) | 4m30s | 3m53s | −14% |

Wall clock **6m2s → 5m8s**. `--rerun` landed exactly where predicted: attempt 1 went 115s → 136s
because it now carries the compile the others used to repeat, and attempts 2 and 3 went 99s → 76s
and 75s.

**What would move the wall further, and was not done:** the three attempts as a matrix of three
jobs rather than a loop in one, which would make that job cost one attempt (~1m45s) and take the
run to about 4m, bounded by Windows instead. Three fresh runners are arguably stronger evidence of
non-determinism — but they are not the *same* evidence, since the current shape also runs against
a workspace the previous attempt dirtied. Changing the shape of the check that catches flakes is
its own issue, not a footnote in a speed PR.

## [2026-08-13] #234 — the vault is not only records ✅

`reconcile()` is `git add --all`, and once the vault was worth opening, Obsidian's own state
started arriving in commits. The 23:00 backup on 2026-08-12 committed **one line of graph zoom and
nothing else**, under `chore: reconcile uncommitted records`.

**Option B, and not A.** Scoping the staging to what the server writes is honest by construction
and trades a visible annoyance for an invisible one: if the server ever writes outside the list,
reconciliation stops catching it and a record goes uncommitted. That net exists because the tidy
path already missed something once. `.obsidian/` is ignored instead —
[[decisions/2026-08-13-the-vault-is-not-only-records]].

**A whole directory, never a list of files inside it** (#122's lesson). Obsidian adds files on its
own schedule.

Two things this does **not** do, both stated in the PR rather than done quietly:

- it does not untrack what is already tracked — `git rm --cached -r .obsidian` is the owner's to
  run, on their own published history
- it does not fix the shape. `reconcile` still commits an unfinished note or a dropped screenshot.
  Option A remains the only fix for that, and its risk is unchanged.

**Not done yet: the runner numbers.** A laptop with fourteen cores is not evidence about a runner
with four. If CI does not show the saving, this comes back out.

## [2026-08-13] #248 — a page said it was solved in a language it was never submitted in ✅

Found by reading a problem page end to end rather than the code that writes it.

```yaml
language: "python3"     # ← from a run that produced no verdict
verdict: PASS           # ← from the only submit, which was java
attempts: 1
```

Lesson 120802: run java, **submit java PASS**, run java, run python3. The page took its language
from the newest record of any kind and its verdict from the newest submit — two fields one line
apart answering about two different gradings.

`attempts`, `lastSubmit` and the whole attempt table were already submit-only. `language` was the
single field on the page that counted runs, and the one a reader pairs with the outcome. Now the
newest submit's, and absent when nothing has been submitted — borrowing a run's language would
name a language nothing was ever judged in.

**Fourth place today with the same root** (#235 `stats`, #237 `get_problem`, #241 the tag map's
node size, now this). *A run is not an attempt* is design §5.1, and every one of these had to be
told separately. `SubmissionRecord.isSubmission()` from #237 is the one place that now says it;
this page reaches it through `submits()`, which predates it.

Catalog metadata — title, level, part, acceptanceRate, tags — still reads every record. Those are
identical whichever grading carried them.

## [2026-08-13] #252 — progress.md takes the union, as log.md always has ✅

Five merge conflicts in one day — #247, #249, #251 and two more — every one of them this file, and
every one resolved by keeping both sides. The gate requires each branch to update it, so two open
branches always collide at the tail and the resolution is never a judgement.

`.gitattributes` already carried the answer for `log.md` and `index.md`, and the wiki schema says
why: *append entries start with the date, so conflicts resolve deterministically via chronological
union.* This file is append-only in the same way, dated in the same way, and was simply left out.

**Verified rather than assumed.** Two temporary branches, each appending a dated section, merged:
0 conflict markers, both appends present. The same merge conflicted every time earlier today.

The one real cost, stated because `union` is not free: it keeps both sides **with no marker**, so
two branches editing the *same existing line* would silently keep both copies. That is wrong for a
file edited in place and right for one that only grows a section at the end — which is what this
file and `log.md` are. A duplicated paragraph is visible in review; a conflict that resolves to
"keep both" every single time is not.
## [2026-08-13] #250 — a tag says where you stand, so the graph can colour it ✅

Owner asked for attempted-but-not-passed types in red. The counts supported it and the *query* did
not: an Obsidian colour group takes a search query, not an expression, so "tried and not passed"
was `-["attempted":0] ["solved":0]` — correct, and nobody's idea of a setting. The owner's first
attempt at the simpler `solved:0` had already matched all 83 tags for want of brackets.

The note now carries `status: untouched | attempted | passed`, so each group is one exact match.

**Reused `ProblemStatus` rather than inventing a vocabulary.** `list_problems` has answered in
those three words since #100 and derives them the same way; a second set for tags would be the
`submissionCount`/`attempts` split all over again (#237). Derived on read, never stored — a
stored copy can disagree with the two counts it summarises.

**It names a state, not a weakness.** Nothing says `attempted` is bad. That boundary is
[[decisions/2026-08-12-the-server-counts-and-names-nothing]] and it has not moved: the server
writes where you stand, the reader decides what to do about it — including which colour it
deserves.

Recipe in both template twins, replacing the `["solved":0]` line #241 added.

## [2026-08-13] #254 — the vault gets a dashboard, and the server seeds it ✅

The owner wanted the table view their other vault has. It turned out to be reachable without
building anything the design had rejected.

**`_dashboard.md` was never forbidden — it had no owner.** §5.5's amendment (#227) sorted the five
phantom notes: `_dashboard`, `_review`, `_warmup` are *aggregation — allowed, unbuilt*; only
`_weakness` was interpretation. What aged badly was the **shape**, not the feature.

An Obsidian Base is a query, not data:

| | `_dashboard.md` as specified | `dashboard.base` |
|---|---|---|
| what | server-generated Markdown, derived data | a view definition |
| freshness | rewritten per record, stale in between | evaluated live, cannot go stale |
| server cost | every submit | once, if absent |

§5.5 had already argued for it — *"tables, filters, sorting and aggregation all come for free"*.

**Seeded by the server, not shipped in the template**, on the owner's suggestion and it is the
better call: a template is copied once at creation, so every repository that already exists would
never receive it — the staleness that hit the vault README twice today. The image ships `src` and
not `template/`, so the copy lives in `src/main/resources/vault/` and there is only one.

**Written only when absent, never regenerated.** The tag notes and problem pages are derived data
and are rewritten whole; a `.base` becomes the reader's the moment they add a column. Same posture
as `RecordRepositoryIgnores`.

Verified live, both directions: removed the hand-placed copy → the server wrote it on boot
(`Wrote /records/dashboard.base`); appended a line by hand → restarted → **md5 unchanged**.

### Two things the data forced, and one the constitution did

- `file.name` is `README` for every problem, so it is useless as a label. `title` leads and
  `file.name` trails as the clickable `Open` column.
- Tag notes are keyed by slug (`binary-search`) while `tag` holds the real name (`binary_search`).
- **The labels were Korean and the guard caught it.** `ProblemReadme` had already drawn the line —
  *our own labels are English; the tag words come from the data and stay verbatim* — and I had
  written six Korean view names straight past it.

Default order is recent-first. Shipping it sorted worst-first would be the server naming a
weakness, which [[decisions/2026-08-12-the-server-counts-and-names-nothing]] forbids.

## [2026-08-13] #256 — the record says algorithm or SQL, which the server knew all along ✅

Surfaced by the dashboard work: the owner asked whether SQL and algorithm were separated, and the
honest answer was "only by inference" — `language=mysql` or a part name, both of which break the
day Programmers renames either.

**We were not missing the data; we were dropping it.** `ProblemKind.ALGORITHM|DATABASE` has been
part of `ChannelKey` from the start — the server picks `Challenge::AlgorithmChannel` against
`Challenge::DatabaseChannel` by it before the first frame arrives (protocol §2). Both
`SettledCapture` construction sites already held the channel. One field, threaded through.

Measured first, and the measurement mattered twice:

- the wire value is **`database`, not `sql`** — a guess would have shipped a word the protocol
  never uses. `@SerialName` pins both spellings.
- every frame type was checked for which carries `challengeable_type`: `start` carries it for
  both kinds, so a record that exists always had a kind available. It still comes from the
  channel, not the frame — the channel is a value we chose, strict by dev rules §4, and known
  before anything arrives.

Null on records from before the field, like every schema addition — a defaulted kind would name a
channel we never subscribed to. MCP emits it for free (the projection is derived from the stored
record), and the dashboard gains **By kind**, the axis the owner actually asked about.

## [2026-08-13] #256 merged and deployed; the seeded-file tension showed itself immediately

`a21775d`. Rebuilt, restarted, `Records are stamped in Asia/Seoul` on boot.

**The vault's dashboard did not gain the By kind view, and that is the design working**: the file
existed, so the server refused to touch it. For this vault the file was an unedited copy of v1
(checked against git before acting), so it was replaced by hand with the new shipped version.

Recorded because it is #258's problem statement in miniature: **write-if-absent protects edits at
the price of never delivering improvements.** A vault that has customised the file keeps its
customisation and misses By kind; the README has the same shape. Whatever #258 decides for the
README should say whether "seeded" means v1-forever-unless-deleted, and the template README now
says exactly that in its own words ("written once… the server will not touch it again").

`kind` lands in records from the next grading; the four existing records stay kind-less by
design. Live verification of `"kind":"database"` needs one SQL run, which is the owner's browser.

## [2026-08-13] #258 — the server prepares the record repository; the template retires ⏳

Owner-steered through four decisions in one afternoon: init too, README.md as the seeded name,
compose defaults the path, and **a GitHub token replaces the SSH path as the primary remote**.
The token concern was raised, reaffirmed, and contained structurally — private hardcoded and
re-verified from the response (the public-answer refusal is its own test), the token masked
everywhere and stored owner-only inside the gitignored `.ps/`, an existing origin never touched,
and the `.env` line removable after one boot.

Everything the server now does is smaller than something it already did: it wrote into the
directory, committed and pushed — what was left to the user was exactly the part that rotted.

`template/` is gone. Its last two unique files (the READMEs) are seeds beside `dashboard.base`
in `src/main/resources/vault/`, its four ignore rules live in `RecordRepositoryIgnores`, and the
structure-block guard retargeted without losing a single assertion. `bootstrap.md` §2 is now
"there is nothing to run", in both languages.

ADR: [[decisions/2026-08-13-the-server-prepares-the-repository]].
## [2026-08-13] #261 — CONTRIBUTING gets the sixth Korean twin, by owner decision ✅

The constitution pinned the twins at five and the ADR had rejected contributor-document
translation, naming CONTRIBUTING.md. The conflict was raised; the owner reaffirmed; the ADR is
amended rather than superseded, because the reconciliation is real: **the rejection was about
what contributors produce, and this document tells a Korean speaker how to produce it in
English.** Translating the instructions strengthens the rule they teach.

One PR carries all four places the number lived: the twin itself, CLAUDE.md's list (5→6, "a
seventh remains a change to the list"), the ADR amendment, and bootstrap.md + its twin which
also said "five".

## [2026-08-13] #263 — off 8080, onto a port nobody else wants ✅ `e09a0c5`

8080 collides with half of what a developer already runs. **1619** spells "PS" — P is the 16th
letter, S the 19th — is above 1024, and is none of the crowded ones (3000 · 5000 · 5173 · 8000 ·
808x · 8888 · 9090). One `TRACKER_PORT` away for anyone who disagrees.

Thirteen files, every occurrence: `application.yml`, the Dockerfile healthcheck, compose's port
mapping, the CI job's probes, **the extension's default in two places** (`background.js`
`DEFAULTS` and `options.js`), and every document that wrote `127.0.0.1:8080` including all four
Korean twins.

Migration for an existing install: nothing, if `TRACKER_PORT` is pinned in `.env`. Otherwise the
MCP client config and the extension's saved port both move — the extension only changes for a
user who never opened its options page, since a saved value wins over the default.

**And one the sweep missed, caught by the owner reading the actual UI.** `extension/options.html`
said *"Match `TRACKER_PORT`. The default is 8080."* — the replacement pass had run over a file
list built with `--include` filters for `.kt/.yml/.js/.md/Dockerfile`, and `.html` was not among
them. Every file the filter covered was correct; the filter was the defect. Re-swept with no
extension filter at all: the only remaining `8080` in the tree is this file's own history, which
is right, because those sentences were true when they were written.

Third time today a filter or a memo stood in for the world and something outside it disagreed —
grep over the tree, CI over the platforms, and now the owner over the rendered page.


## [2026-08-13] #266 — the options page sent you to the one directory the token must not be in ⏳

`extension/options.html` told the reader the watch token is "`.ps/watch-token` in your record
repository." It is not there and must never be: #126 kept `session` and `watch-token` out of the
record repository precisely because they are credentials and that repository is pushed.

Measured on the live install while running the e2e sweep:

```
~/Desktop/ps-records/.ps/  →  raw/  backup.json  git-credentials  timers.json   (no token)
<checkout>/.ps/            →  session  watch-token                              (the token)
```

So the page is worse than a dead end. The reader looks in the named directory, finds a
*different* credential sitting there, and learns that credentials belong inside the repository
they push.

**The same sentence was fixed in `extension/README.md` on 2026-08-11** — the pass that also
corrected "reads five identifiers / sends two fields". The document *about* the extension was
corrected; the extension's own UI, the page you are looking at while pasting the token, was not.

That is the second time in one day that `extension/options.html` was invisible to a sweep, after
the 8080 → 1619 port pass missed it for a filter that omitted `.html` (#263). Re-swept the whole
tree with no filter at all: every remaining "record repository" near "token" is a correct
statement — `README.md` saying *not* the record repository, the ADR's own title, and this file's
history. No guard added. A guard for one sentence over-fits, and the defect was never a missing
check; it was a file list standing in for the tree.

## [2026-08-13] #267 — the pointer was written into the repository the host shares ⏳

Found by the e2e sweep, on the first host-side push after the token landed:

```
$ git -C ~/Desktop/ps-records push --dry-run origin main
fatal: unable to get credential storage lock in 1000 ms: No such file or directory
To https://github.com/BrokenFinger98/ps-records.git
   6e0b3ff..5e58a35  main -> main
```

The push **succeeded**. `osxkeychain` from `/opt/homebrew/etc/gitconfig` answers first, so
authentication was never the problem; the `store` helper #258 wrote into the record repository's
own `.git/config` then tried to lock `/records/.ps/git-credentials` — a **container** path, in a
file the host shares. On a machine with no second helper there is no credential at all.

The pointer is passed per invocation now (`git -c credential.helper=…`) and persisted nowhere:
the host case, the container case and the native case stop being three different things to get
right. Writing it into the container's global config was the near miss — it keeps a bare
`git push` working inside the container, and on a `./gradlew bootRun` it writes the *developer's*
`~/.gitconfig`, which is the same trespass one directory over.

Two things the fix is not a one-line deletion for. The stale entry is **removed on boot with or
without a token**, because nothing else ever will — and by reading, filtering and rewriting the
values rather than by a regex, since `store --file=~/.git-credentials` is git's documented
default and a looser filter deletes the user's own setting. And the pointer is gated on the
credential **file**, never on `GITHUB_TOKEN`, or `compose.yaml`'s "you may delete this line after
the first boot" fails the first time the container is recreated.

**Verified live, not only against the fake.** After rebuild: the boot log said `Removed our
credential pointer from the record repository's config`, `--get-all credential.helper` was empty,
the host `push --dry-run` reported `main -> main` with no `fatal:`, and the server's own command
form authenticated from inside the container. One cost measured and accepted: a bare
`docker compose exec tracker git push` now says `could not read Username` — the same `-c` makes
it work, no maintained page relies on it, and it is written down rather than discovered later.

Second time today the outside reference was the tool actually running rather than anything that
reads code: the fake GitHub agreed with every request we make and had no opinion about the file
we leave in someone else's repository.

**One test was written for one platform.** `emits an absolute path even from a relative root`
built its relative path with `Path.relativize`, which on Windows **throws** when the two paths
sit on different drives — and on a GitHub runner the temp directory and the workspace do. Caught
by CI, which is the only reader that has ever had an opinion about Windows here (#264 was the
same lesson twice in one PR).

Rewritten as the two properties it had been conflating: a relative root becomes absolute
(measured against the working directory, no relativize anywhere), and `..` is normalised away.
Both are deterministic on every platform, and splitting them says which one broke when one does.

## [2026-08-13] #270 — the dashboard split algorithm from SQL and the graph could not ⏳

`dashboard.base` got a **By kind** view in #256; the graph got nothing. The vault README's
Groups table — the only place graph guidance can live, since `.obsidian/` is gitignored and the
server correctly writes no editor configuration — listed `status` and `path:problems` and no
`kind`. So the one split the dashboard considers worth its own view was the one the graph could
not express.

Two rows added, plus the two-pane answer: Obsidian keeps settings per pane, so splitting the
graph tab and filtering one pane per kind gives both maps at once.

**Said honestly rather than sold.** `kind` comes from the channel the server subscribed on, so
records from before that field have none and those notes join neither group — measured, all 44
records on disk predate it, because #257 merged at 11:54 and the newest record is from the day
before. Until such a problem is solved again, `["language":"mysql"]` is what finds the SQL ones.
A page that promised a working split today would be wrong today.

Korean twin moved with it and its `translated-from` hash bumped.

## [2026-08-13] #273 — a column declared and never shown, found by the owner reading the dashboard ⏳

`dashboard.base` declared `note.kind: displayName: Kind` and then put `kind` in **no view's**
`order`. `By kind` groups by it, so the split renders as headers; `Recent`, the view the vault
opens on, said nothing about it. A declared property no view uses is the tell — it was meant to
be a column and never became one.

Added to `Recent` and `Not passed yet` — the two views that answer "what is going on", and for
the second the question *is* whether the failures cluster on one side. Not to `By kind` (it
groups by it), `By language` (language implies it) or `By attempts` (that view is about counts
and carries no language either).

**And #257 stopped being theoretical on the same screen.** `kind` merged at 11:54 and until 16:29
not one record on disk carried it — nothing had been graded since. The owner solved lesson 151136
in mysql:

```
2026-08-13T16:29:14  lesson 151136  submit  kind='database'  lang=mysql  verdict=PASS
```

frontmatter `kind: database`, badge `submit PASS 1/1`. That is the whole capture path — sensor →
/watch → cable → record → note → commit — proven live for the first time since the field existed,
and with the `database` value specifically, which no algorithm problem could have shown.

Fourth outside reference today, and the second time it was the owner looking at rendered output
rather than anything that reads code (#263 was the 8080 in the options page).

## [2026-08-13] #272 — the coverage gate named a directory and read 13% of it ⏳

The owner asked why the `coverage report` job enforces nothing. A threshold did exist —
`verifyCalculatorCoverage`, 95% branch on `domain/calc`, in the `gates` job, passing at 96%. It
was reading an eighth of what it named: Kover emits `domain/calc/runner` as a **separate**
`<package>`, and the lookup was an exact-name regex, so 862 branches in the same directory were
never measured. 287 of them uncovered — the largest such pool in the repository, inside the one
place we told ourselves was held to 95%. The function's own comment argued for the shape that
caused it: *"Reads the package's own counter rather than summing classes, so a class added later
is included without touching this."* True of classes, false of sub-packages.

**A global threshold was measured and refused.** Branch read 75% overall, and the biggest
contributor was not missing tests: Kotlin compiles each default parameter value into a bitmask
test, so `SubmissionRecord`'s 15 defaulted fields emit 69 branches inside `<init>` that no call
site takes both ways. `domain` read 100% line / 57% branch almost entirely from that. **I first
blamed generated `equals`/`hashCode` — inferred, not measured, and wrong; the method-level
counters said `<init>`.** Corrected in the issue before the plan was built on it.

Excluding runners and generated members the tree measures **85% over 1,733 branches**, which is
what made a per-package floor workable at all.

`verifyBranchCoverage` replaces it and **reads every package in the report rather than names it
remembers** — iterating what is there instead of looking up what we thought was there is what
makes the original defect impossible rather than noticeable. Floors: 80 general, 95 `domain/calc`,
65 `adapter/config`, `domain/calc/runner` exempt. Every deviation carries its argument, and a rule
naming a package that no longer exists fails the build.

**Where the floor was reachable, tests were written rather than the floor lowered** —
`adapter/catalog` 23→80, `adapter/cable` 77→86, `adapter/git` 78→81, `adapter/config` 36→65. Every
one turned out to be an absence path this project claims to care about: a lesson the catalog does
not have, a channel nobody subscribed to, a throwable with no message, a GitHub answer that is
neither created nor already-exists.

One production change: `ClasspathProblemCatalog.resource` chained safe calls onto `bufferedReader()`
and `use`, neither of which can return null, so it carried two branches no test could reach. Early
return instead — an unreachable branch inside a coverage floor is a number nobody can move
honestly.

**And the gate was made to fail before being trusted.** Floor at 90 named four packages; the exempt
package renamed produced *"a rule that matches nothing rules nothing and hides that it does."*
Both reverted. That is the check the old gate would have failed.

## [2026-08-13] #275 — the records knew how you failed and not what was asked ⏳

Six MCP tools returned metadata and gradings and **none could say what the problem asked.** The
vault had the same hole: a problem note was a history of attempts against a question the note did
not contain.

The owner raised it with BaekjoonHub, which has copied statements into users' repositories for
years. **My first objection was wrong and I withdrew it**: I argued Baekjoon's statements have
mixed provenance while Programmers' are Grepp's own, so the precedent would not transfer — and the
owner produced a Programmers example (lesson 276036, full statement, **public** repository). Same
rights holder, same practice, public where ours is private. What survives is narrower: no
complaint is evidence about risk, not about rights.

**It rides on a fetch we already make.** `CodeAttachment` downloads the problem page for the saved
code; the statement is on that same page, so `CodeFetch.Fetched` carries both and Programmers sees
no extra request.

**A file of its own, written once.** `README.md` is regenerated on every grading, so a statement
inside it would need re-fetching every time to survive — the same trap that blocked §6.9's
retrospectives, one file over. `statement.md` is write-if-absent and the README embeds it with
`![[statement]]`, so the regeneration boundary stays clean and the reader still sees one page.

**Parsed with jsoup, not regex.** The two parsers beside it read one attribute off one tag, which
regex does honestly; a nested document is not that, and #272's ADR was written the same day about
matching nested markup by hand. Programmers renders the author's Markdown into HTML and leaves
`class="markdown"` saying so, which is what makes converting it back well-defined rather than a
guess. Measured across lessons 120802, 12916, 151136, 17676 and 42894: twenty tags, **no nested
`<div>`**, which is what makes the region's boundary unambiguous.

**Verified against the five real captures, not only the fixture** — tables, headings, lists and
inline code all land correctly, and one defect showed up only there: lesson 17676 wraps every
worked example in `<p>` inside `<li>`, which the keep-what-you-do-not-know fallback printed as raw
HTML. Fixed and pinned. The fixture in this repository is structurally verbatim with **every word
invented** — #275 forbids a real statement here outright.

## [2026-08-13] #278 — the statement was stored and not served ⏳

#275 was justified by one sentence — *an AI reading `get_problem` knows testcase 3 failed and has
no idea what was wanted* — and storing the statement left that sentence exactly as true as it
was. The vault gained a page; the MCP surface gained nothing.

`get_problem` now carries `statement`, read through a new outbound port. Read-only like the rest
of the slice: `RecordQuery` still has no `RecordWriter` and no `GitSync` in reach, so the sentence
[[decisions/2026-08-06-mcp-read-slice]] rests on stays true word for word.

Only `get_problem`. `submissions` and `list_problems` answer across many problems and would turn
into pages of prose; the tool that is already "everything about one lesson" is where a statement
belongs. Absent when none was captured — never `""`, which reads as a problem with no description
rather than one we never fetched. An unreadable file answers absent too: a statement is the one
thing here a reader can do without, and it must not take the rest of the answer down with it.

## [2026-08-13] #280 — a problem solved before the statement existed never got one ⏳

Caught by the owner asking "본문 긁어오는거는 다 한거야?" — and the honest answer was no. Measured
right after #279 merged: **0 `statement.md` files**, 5 problems with records, and **0 records
`codePending`** (48 log lines, 24 after resolving corrections). `attachPending` only revisits
pending records, so those five would have got a statement only by being solved again. Someone
adopting the tool after a year of solving keeps a year without one, and the gap grows with the
history rather than shrinking.

It also left a retry hole: a first attachment that hit a rate limit never fetched the statement
again for that problem, ever.

**Nothing in the pass needs a request to decide what to do.** The record log already carries
`lessonId`, `title` and `language` per problem, and `ProblemStatements` says which have a file.
Requests go out only for the ones that do not — and one per *problem*, not per record, so a
problem solved in four languages is one page.

**Its own fetch path, deliberately.** At capture time the statement rides on the code fetch for
free; here the code is already attached and `ProblemPageCodeFetcher` gives up with `Unavailable`
on a page with no saved-code input *before* it looks at a statement. Two readers of one page is
not duplication when one is a free ride and the other is a one-time repair.

**Courtesy is the shape, not a footnote** (§9.3): 20 per boot, a 2-second pause between, and a
stop at the first blocking answer — an expired session is shared by every remaining problem, so
learning it 299 more times is only rude. A backlog drains over boots; nobody is waiting on it.

Placed after `attachPending` and **before** `refreshVault()`, because the vault refresh is what
puts `![[statement]]` on the problem page — a README regenerated first would link nothing until
the next boot.

## [2026-08-14] #282 — an exemption that also hid its number ⏳

`verifyBranchCoverage`, written hours earlier in #276, filtered exempt packages out **before** it
logged. So `domain/calc/runner` — the one package with an exemption, and the one whose exemption
promised that "raising it is tracked separately" — was the one package whose number never
appeared. A guard that hides what it declined to enforce is
[[decisions/2026-08-10-guards-must-prove-they-ran]] again, in a guard written the same day.

It mattered immediately. **The 66% quoted in #272 was measured locally**, and locally
`CsharpRunnerExecutionTest` skips all 8 of its tests: this machine's dotnet is an x86_64 install
on arm64 and cannot start, which the probe correctly reports as absent (not a defect — the
comment beside it already names that case). `CsharpRunner` holds 51 missed branches, the most of
any class in the package. On `ubuntu-latest`, where the gate runs, those tests execute — so the
real number was higher than the one in the ADR and nobody could see it.

Every package is reported now; only the ones with a floor are enforced, and an exempt row says so:

```
  domain/calc/runner        66%  (575/862 branches)  — exempt
```

**Also a correction to what I recommended earlier today.** I proposed "the runner execution proof"
as the next piece of work on the premise that the runners had no execution tests. They have had
them all along — seven `*RunnerExecutionTest` classes, compiling and running generated code for
real, with exactly the `assumeTrue` posture I was about to suggest inventing (#84, #86). 49 tests,
8 skipped locally for the broken dotnet and none anywhere else. The premise was wrong and the
recommendation with it.

**And the number, once visible, disproved the reason I wanted to see it.** I expected CI to read
higher than the local 66% because the 8 C# execution tests skip here. Downloaded the CI test
results rather than reasoning about them: **49 execution tests, 0 skipped**, C# included — and
`domain/calc/runner` reads **575/862 on both**, to the branch.

So those 8 tests add *no* branch coverage to the package. `CsharpRunnerTest` already exercises the
generation branches; the execution test compiles and runs what was generated, which proves it is
correct without visiting a new path. The 287 uncovered branches are uncovered by every test there
is, unit and execution alike, and #272's exemption rests on the number it said it did.

The claim I put in #283's PR body — *"the real figure is higher than the one recorded"* — was a
hypothesis stated as a finding, and it was wrong. Corrected there and here. The reporting fix
still earned itself in the same hour: a number nobody could see is a number nobody can be wrong
about out loud.

## [2026-08-14] #286 — the AI was told what the tools return and never how to read them ⏳

`docs/mcp.md` is careful about how these records mislead — *"`elapsedSec` is not how long you
spent"*, *"missing data looks missing"*, *"a history with holes says so"*. **A human reads that
document; the model never opens it.** What the model gets is the MCP `instructions` string, and it
was one sentence. Every trap the document warns a human about was one the model walked into
unwarned, and the model is the reader this surface was built for (design §7).

The instructions now carry three kinds of thing, none of which is the server interpreting: which
tool answers which question, the readings that have **actually** gone wrong (a run counted as an
attempt — #235 and #237 both; wall clock read as effort, 77,251 against 37 on one measured record;
absent read as zero; a conclusion drawn over `incompleteHistory`), and what the data cannot speak
about at all — there is no cohort here, so "slow" only ever means slow against this learner's own
other passes.

And the part that is the reader's, said **to** the model rather than about it: the server counts
and names nothing, so deciding which number is a weakness is being handed over rather than
withheld.

**The test that pins the line had to be rewritten before it was right.** The first version
blacklisted words — `weak`, `struggling`, `should practise` — and failed immediately, because the
text says *"it will not tell you which of these numbers is a weakness"*. Refusing to name a
weakness requires the word. The assertion now bans **shapes** (`you are `, `the learner is`,
`you tend`), which a refusal cannot accidentally contain.

`docs/mcp.md` and its twin also caught up with today: `statement` (#275/#278) and `kind` (#256)
were undocumented there.

## [2026-08-14] #288 — a public repository holding a login and a token had no security policy ⏳

Every piece of it already existed and none of it was in the file GitHub shows: `docs/mcp.md` has
the MCP posture, `docs/bootstrap.md` says where each credential lives, `CLAUDE.md` forbids any of
them reaching a log, and three ADRs cover the network posture, the read-only slice and the token
that retired SSH. Five files, and the one a reporter opens was not among them.

`SECURITY.md` is an entry point rather than a copy — it links to where each claim is implemented,
because a security document that restates the code is one that will disagree with it.

Two sections earn their place. **"What this holds, and where"** — the three secrets, and the fact
that two deliberately do not live with the records, because the record repository is pushed and a
credential inside it would be pushed too (#126). **"What is not a vulnerability here"** — the
`/watch` token is a loopback gate rather than an authentication system, anything with local
filesystem access has already won, and the records repository is private by construction rather
than by permission checks. Saying so costs a paragraph and saves somebody a weekend.

And one boundary worth drawing in public: **using a private protocol is not a security
vulnerability.** README already takes a position on it; filing it as a CVE-shaped thing makes both
conversations harder, and the document says which conversation to have.

Measured while writing it: `private-vulnerability-reporting` is **disabled** on this repository
while secret scanning and push protection are enabled. So the document says to use the private
report *if offered* and otherwise to ask for a channel without publishing details — accurate today
and simpler the moment that setting is flipped, which is the owner's to flip.

Every path and anchor it names was checked to resolve, including the two deep links into
`docs/mcp.md` and `README.md`.

## [2026-08-14] #293 — the vault rendered in Obsidian and broke in the two places records are read ⏳

The owner opened a problem page on github.com and saw `![[statement]]` printed as literal text.
Then IntelliJ, same. Not a broken link — **not a link at all** in either.

Wikilinks are Obsidian's own syntax, and of the three renderers these records are opened in,
exactly one understands them:

| renderer | `[[wikilink]]` | `![[embed]]` | `[text](path.md)` |
|---|---|---|---|
| Obsidian | ✅ | ✅ | ✅ |
| github.com | ❌ literal | ❌ literal | ✅ |
| IntelliJ preview | ❌ literal | ❌ literal | ✅ |

**Every link the server writes was a wikilink** — the statement, the tag links on each problem
page, the problem links on each tag note. So a record repository was fully legible in Obsidian and
half-broken everywhere else, *including the host it is pushed to*. #292 said github.com had no
index; the truth was that the pages themselves did not render there.

All three now emit standard markdown links. The paths are safe to write raw because `slugOf`
collapses everything that is not a letter or a number — measured against the shipped catalog,
whose worst titles are `스티커 모으기(2)` → `스티커-모으기-2` and `[1차] 추석 트래픽` →
`1차-추석-트래픽`. Nothing that can reach a link's parentheses.

**The cost is stated rather than hidden: the statement stops being embedded.** No syntax embeds in
Obsidian *and* renders on GitHub — `![](statement.md)` embeds in one and is a broken **image** in
the other. Readable in three beats inlined in one, for a file sitting in the same directory.

Seven tests failed on the change, which is the correct number: they were pinning the format on
purpose. The strongest of them got stronger — `every link a note emits resolves to a file that
exists` (#233's guard) now resolves each link **from the note's own directory**, which is what a
relative link means and what a renderer will actually do with it.

**One claim I cannot verify from here**: that Obsidian's graph still draws edges from markdown
links. The documentation says both formats are equivalent, and the tag map rests on those edges
(#229, #241) — so it is the first thing to look at in Obsidian after this lands.

## [2026-08-14] #292 — an index github.com can render ⏳

Opening the record repository in a browser showed nothing you had solved: `dashboard.base` renders
as raw YAML because it is an Obsidian query and Obsidian is not there, the root `README.md` is a
usage guide, `problems/` is a folder listing, and the log is machine-readable. Everything the
repository knows was in it and none of it legible where a browser opens.

`problems/README.md`, because **GitHub renders a README at the root of any directory** — and
browsing into `problems/` is exactly where the question gets asked. It also adds **no fourth kind
of file**: it is regenerated from the records like every problem page and tag note beside it.

Two shapes were costed and rejected. **Markers in the root README** (`<!-- index:start -->`) put
the table where GitHub shows it first and make one file *half* server-owned — the ambiguity that
blocked §6.9 and that `statement.md` was split out to avoid — and an existing vault has no markers
anyway, since that seed is write-if-absent. **The root README becoming the index** demotes a page
carrying the graph groups, the `.ps/` warning and the timezone note, and moves a Korean twin and
its `translated-from` hash with it.

**Two tests failed and the behaviour was wrong, not the tests.** `a boot with nothing left behind
commits nothing` broke because the index was written even for an empty history — manufacturing a
commit to announce that nothing had happened, in the one place `StartupReconciliation` states out
loud that a boot with nothing to recover does nothing. No records now means no file, which is also
the honest answer: an index of an empty directory helps nobody.

Counts and never a verdict — how many recorded and how many passed are facts, and newest-first is
an ordering of when things happened rather than a ranking of them.

## [2026-08-14] #296 — the statement on the page itself ⏳

The owner asked why the statement is a link rather than just being in the README. The honest
answer: **my original objection had expired.** #275 chose an embed over inlining because this file
is regenerated on every grading, so a statement inside it would have to be re-fetched every time to
survive. That stopped being true the moment `statement.md` existed — the text is on disk and
inlining costs a file read.

Then #293 replaced the embed with a link, correctly, and the one thing it cost was **one page**.
Inlining gives it back in all three renderers instead of the one Obsidian gave it in.

`statement.md` stays. This is derived and that is the source: delete the derived file and nothing
is lost, delete the source and the only way back is a request. It is also the copy a reader may
annotate, which is the same argument `notes.md` rests on.

**Placed last, against what the issue first proposed.** A KAKAO statement runs several kilobytes,
so putting it first pushes every page's attempt history below the fold — and the history is the
half a *record* is opened for. Last also keeps the top of every problem page the same shape
whatever the statement's length.

**A new cost, and the owner's graph screenshot is what made it visible**: the link had made
`statement` a node with an edge, six of them on the map right now. Inlining removes the link and
those become isolated dots. Filterable in Obsidian (`-path:statement`), and the tag map is
unaffected because it is built from problem↔tag edges — but named rather than discovered.

**And that same screenshot closed #293's open question.** Markdown links do draw edges in Obsidian:
README↔tag edges are on the map and the colour groups still fire. That was the one claim #293
shipped without being able to verify from outside Obsidian.

## [2026-08-14] #300 — a seeded file never received an improvement ⏳

`README.md`, `README.ko.md` and `dashboard.base` are seeded write-if-absent (#254), on a rule that
is right and stays: *editing is respected forever; deletion is read as loss, not intent.*

Absent and edited were the only two states it considered, which left a third — **untouched, and
stale**. Twice on 2026-08-13 an improvement reached the shipped seed and the one existing vault
only because I edited it by hand: the graph's `kind` colour groups (#271) and the `Kind` column
(#274). Every future seed change has that shape, and the count of hand-patches only grows.

`SeedLedger` records the SHA-256 of what the server wrote, in `.ps/seeds.json` beside the timers
and the backup marker (#126). A file that still hashes to that is ours to replace; **one character
different and it is theirs, permanently.** Every word of #254 stays true and the case it did not
consider is answered.

**No record means edited.** A vault seeded before the ledger has none, and absence is not
permission — the mistake runs one way only. Overwriting what a person wrote cannot be undone from
here; leaving a stale file costs a hand-copy that was already the status quo. A corrupt ledger
reads as empty for the same reason.

Rejected: shipping the bytes of every version ever seeded (grows without bound, needs a release
process this project does not have), and a marker inside the file (visible in a document meant to
be read, deletable by accident, and it puts the tool's bookkeeping in the reader's face).

## [2026-08-14] #272 follow-up — the runner exemption, from 66% to 78% ⏳

`domain/calc/runner` was the largest untested surface in the repository: 287 branches no test
reached, and #285 had already corrected *why* — the seven execution suites exist, run 49 tests on
CI with none skipped, and move the number by nothing, because the generation branches are what is
uncovered and executing generated code proves it correct without visiting a new path.

Two table-driven suites, **679/862 = 78%** now, 104 branches taken. `RunnerValueTableTest` walks
every declared type each generator renders and every value it must refuse; `StdinRunnerTableTest`
does the `main`-reads-stdin half for all seven languages. Table-driven because the shapes are a
matrix, and a language missing from the list shows up as a gap — the shape §6.2 already uses for
the compile-error fixtures, after #212 classified six of seven languages by patterns written for
two others.

**Both tables failed on first run because I asserted a refusal the code does not make.** Twice:

- a quoted number for a numeric parameter (`int` ← `"7"`) is **accepted** and rendered `7`;
- an unquoted number as stdin is **accepted** and fed as its own text — while the refusal message
  three lines away says "quoted text".

Neither is measured — no `examples.json` and no fixture has ever carried either shape — so the
code was not changed on a guess. Both are pinned as the behaviour that exists, labelled as never
measured. That is the third and fourth time in two days that an assertion of mine turned out to be
an assumption; the difference is that this time the test was written before the claim reached
anything but a test.

What is left is #302: C's `int arr[], size_t arr_len` pair needs a table shaped around it, C#'s
stdin runner is OS-dependent and lives in the execution suite, and the signature parsers hold the
rest.
## [2026-08-14] #298 — the one CI job that downloaded the world every run ⏳

Six of seven jobs cache `~/.gradle` through `setup-gradle`. The seventh could not: `docker image
boots` runs `docker build`, and the Gradle that matters runs **inside** the image where the host
cache does not reach. So every run re-resolved Spring Boot, Ktor and the rest — from a layer the
Dockerfile already isolates for exactly this purpose and that nothing was caching.

It had already cost a **429 from Maven Central** on one run. A job that re-downloads everything is
one rate limit away from a red build that says nothing about the code.

Built through `docker/build-push-action` with `type=gha` layer cache now. The dependency layer is
reused until `build.gradle.kts`, `settings.gradle.kts` or `gradle.properties` change — which is
what the Dockerfile's layer split was designed to give and had never delivered in CI. `load: true`
keeps the `programmers-tracker:ci` tag for the steps that boot the container and assert its
network posture.

## [2026-08-14] #304 — the server ignored one editor's directory and committed the other's ⏳

`RecordRepositoryIgnores` carries `.obsidian/`, added after a 23:00 backup committed nothing but a
changed graph setting under a message about records (#234). `.idea/` was not there — and measured
on the owner's repository, it was **tracked and pushed**, sitting beside `problems/` and `log/` on
github.com.

The argument that added `.obsidian/` never mentioned Obsidian. It is that `git add --all` cannot
tell an editor's state from a record and the server is the thing doing the committing. This is a
Kotlin project; a vault opened in IntelliJ is not an edge case.

A fifth rule, with the same escape hatch as the fourth. **It does not untrack what is already
committed** — an ignore rule has no effect on a tracked file, and a server that started running
`git rm --cached` on a user's repository would be an ignore rule grown into something else.

## [2026-08-14] #306 — the rule threw away the graph settings to escape the window layout ⏳

The owner asked whether `.obsidian/` needed to be out of GitHub at all. It did not, and the
history said so. Before the rule landed, file-by-file:

```
5 changes  .obsidian/workspace.json     which panes are open, scroll position, window size
5 changes  .obsidian/graph.json         the colour groups and filters
2 changes  core-plugins / appearance / app.json
```

`workspace.json` changes because Obsidian was **opened**. `graph.json` changes because somebody
**configured the graph** — the `["status":"passed"]` and `["kind":"database"]` groups built on
2026-08-13, which took time and thought.

#234's complaint was a backup commit carrying *nothing but a graph setting under a message about
records*. That is a **noise** complaint, and the blanket rule answered it by throwing away the
valuable half — in a tool whose whole argument is that nothing is lost. A laptop dying took those
settings with it while every record survived.

Narrowed to `.obsidian/workspace.json` and its mobile twin. The rest is versioned, so a vault
cloned onto another machine opens **configured** rather than blank.

**And a rule the reader already wrote now wins.** `alreadyIgnores` matched a line exactly, so a
`.gitignore` saying `.obsidian` would have had `.obsidian/workspace.json` appended underneath it —
the server arguing with a broader decision somebody already made. It checks ancestors now.

`.idea/` stays ignored whole (#304): a folder of markdown opened in IntelliJ has no configuration
worth carrying.


---

## 2026-08-14 · #309 — the safe call the type system had already answered

Every build since #295 printed `ProblemIndex.kt:79 Unnecessary safe call on a non-null receiver`.
One character, and the reason to bother is not the character: a warning that appears on **every**
build is how a reader learns to skip the warning block, and #310 was sitting right underneath it.

The half that was not cosmetic is what the safe call was standing on. `SettledCapture.toRecord`
writes `title = problem?.title.orEmpty()`, so **a problem the cached catalog has never seen records
an empty title** — blank is how "unknown" is spelled in the JSONL. `link()` falls back to the
lesson id there, `RecordLayout` names that directory `<lessonId>` with no slug, and the row still
resolves. Nothing tested it; every case in `ProblemIndexTest` passed a real title. Pinned now.

**The remaining warning is #310** and is deliberately not in this branch. `Flow.timeout` on the
observation socket is a preview API, and `@OptIn` is not a suppression — it is a written
acceptance that a coroutines upgrade may break the silence deadline (#94). That is a decision with
an ADR attached, not a line to slip into a typo fix.

---

## 2026-08-14 · #308 — the narrowing that could not reach an existing install

#307 narrowed `.obsidian/` to `.obsidian/workspace.json`, and #308 is the observation that it
therefore reached **only repositories created after it**. `RecordRepositoryIgnores` adds a missing
line and edits nothing — that is the rule, it is in the class's KDoc, and it means an older
`.gitignore` keeps the rules its version had. The new ancestor-aware `alreadyIgnores` then makes
it look deliberate: the server sees `.obsidian/` and correctly declines to add the narrower rule.

**No machinery.** This is #300 one file over — an improvement that cannot reach an existing
install — and the seeds got a ledger because seeds change often. Ignore rules do not: five rules
in the tool's history, one of them ever narrowed. The answer is a sentence, not a migrator, and
the rule's own comment already ends with the instruction (*"Delete this line if you would rather
version your vault's settings"*).

So `docs/bootstrap.md` gains an upgrader's paragraph beside the SSH one, which is the same
register, and the Korean twin gains its translation. The owner's own repository is already
correct — the 2026-08-13 wipe rewrote that `.gitignore` from scratch, so the by-hand deletion the
issue asked for had already happened by accident. Verified: it carries the two narrowed rules and
no `.obsidian/`.

Not done, on purpose: **the server never rewrites or removes a line in that file.** It has always
been the reader's, and a tool that edits the reader's decisions is worse than a stale rule.

---

## 2026-08-14 · #310 — the last build warning, and the test that made it affordable

`Flow.timeout` on the observation socket is `@FlowPreview`. It had warned on every build for
weeks, invisible under #309's warning until that one came out — which is the whole argument for
removing a warning nobody reads.

**The decision was settled by looking for a test, not by reasoning about risk.** `@OptIn` is a
written acceptance that a coroutines upgrade may break the line #94 put above the heartbeat
filter, so the question is what would catch it:

- removal → **compile failure**, loud and unmissable;
- silent change of meaning (gap-between-emissions → total-collection-time) →
  `heartbeats hold the socket open and never reach the capture` runs a 200 ms deadline against a
  flow emitting for 600 ms and asserts zero reconnects. It goes red.

Both caught, so accept. **Had the second row been empty the answer would have been a hand-written
per-emission timer** — `withTimeoutOrNull` is not a drop-in, it bounds the whole collection and
would kill a healthy socket the first time a channel stayed open past the deadline.

ADR: [[decisions/2026-08-14-a-preview-api-under-a-test]], including the cost that deleting that
test silently revokes the basis for the decision — said again in the KDoc at the call site, where
somebody about to delete it will be.

`./gradlew compileKotlin --rerun-tasks` now emits **zero warnings, for the first time in the
project's history.** That is the deliverable: the next warning to appear is one nobody has read.

---

## 2026-08-14 · #302 — the exemption is retired, not raised

`coverageExempt` is **empty**. Every package in the tree now carries an enforced floor.

`domain/calc/runner` was 66% when the exemption was written, went to 78% with the value tables
(#272), and **83%** here — clear of the general 80% floor, so the entry came out of the map rather
than getting a lower floor written for it.

**Measured before writing anything.** The issue said C had ~30 branches left and that `gridLocals`
was unreachable from a single-parameter table; the Kover method counters said **47**, and
`CRunnerTest` had been reaching `gridLocals` all along. The gap was not the happy paths — it was
`scalarLiteral` 9, `gridLocals` 7, `intLiteral` 7, `checkFor` 5, `arrayLocals` 4, `textLiteral` 4,
`blockFor` 4. Almost entirely **refusals**.

C earned its own table for a reason the other six do not have: **one wire value is not one
parameter.** `int n[], size_t n_len` and `int** n, size_t n_rows, size_t n_cols` — so a table keyed
on *one declared type, one value* cannot express it. 54 cases, and the refusals are the half that
matters here more than anywhere: in C a mis-sized array is not a wrong answer, it is a read past
the end of a block.

**54/54 passed on the first run**, which the two previous tables did not — both of those failed
because I asserted refusals the code does not make. The difference was reading `CRunner` end to
end and predicting each arm before writing the assertion.

What made this closable at all is that the number was visible. *"Raising it is tracked
separately"* is the sentence that usually makes an exemption permanent; #283 forced the exempt row
to keep printing its number, so the distance left was a fact on every CI run instead of a promise
in a comment. ADR outcome extended:
[[decisions/2026-08-13-a-floor-per-package-and-a-reason-per-exception]].

**Remaining: 142 branches across the other six generators** — C# 29 (the OS-dependent
`Console.Clear()` is measured in the execution suite and unreachable from a generation test),
Kotlin 20, Python 16, C++ 16, JavaScript 14, Java 14. All above their floor; no exemption, so any
regression fails the build rather than being noted.

---

## 2026-08-14 · #316 — the push a pass triggered did not contain the solution

Found by the first-run e2e, not by reading code. Solving 120811 from a blank vault produced
`[Lv0] 중앙값 구하기 — PASS (9/9…)` — a commit holding **`submissions.jsonl` and
`001.raw.jsonl` and nothing else.** The solution, the statement, the runner, the problem page, the
index and both tag notes were untracked when `pushOnPass` fired, and would have waited for the
23:00 backup. Up to ~24 hours where the off-machine copy of a solved problem has no solution in it.

**I reversed my own recommendation while implementing.** I proposed *moving* the push after the
attachment — one push, tidy. Reading `copiedRawPath` again killed it: *"the verdict is
unrecoverable and the copy is not"*. Moving the push makes the unrecoverable half wait on a
network fetch of the recoverable half, which is exactly why option A was rejected. So the push is
**added**, not moved:

- verdict commit + push — immediate, unchanged, survives a hung fetch or an expired session;
- `RecordWriter.attached` — `reconcile()` then `push()` on a pass, seconds later.

**No branch on how the attachment went**, because `reconcile` is documented idempotent and a no-op
on a clean tree: a deferred attachment finds nothing to add and this degrades to exactly the old
behaviour. `ChannelCapture` calls it whether the attach threw or not.

ADR: [[decisions/2026-08-14-the-push-waits-for-the-fetch-the-commit-does-not]].

**The test's first failure was the fixture, not the code.** Asserting at the remote — where the
defect lives — showed the bare repository escaping every Korean problem directory
(`problems/120804-\353\221\220…`). `GitWorkspace` has set `core.quotePath=false` on the working
repository since it was written, with a comment naming this exact trap; the bare remote never got
it, because until now nothing read *paths* off a remote rather than commit subjects. Fixed at the
fixture.

Costs taken knowingly: two pushes per pass, and a `chore: reconcile` commit per solved problem
instead of one nightly. That message now under-describes what it carries — naming it after its
problem needs a new port operation and is not in this change.

---

## 2026-08-14 · #314 — the seed Obsidian rewrote, and the door that locked behind it

**Confirmed by the owner, after my own reproduction failed.** I claimed the trigger was "having the
vault open"; restoring the commented file and waiting 75 minutes with Obsidian running changed
nothing, and I said so in the issue rather than leaving the claim standing. The owner opened the
dashboard Base view and the hash moved inside the minute: `9fc964011f10` → `a3967cdb98c6`, 15
comment lines to **0**, no additions. Same output hash as the 14:11:37 write, so the transform is
deterministic and that earlier write is explained.

`SeedLedger` then reads the file as edited and never updates that vault's dashboard again — for a
reader who edited nothing. The two plain-markdown seeds were untouched, which is the tell: this hit
the one seed that is not markdown.

**Fixed by shipping Obsidian's own output.** Deletions only, nothing left to delete, so its output
is a fixed point of its own transform — measured, not assumed. The comments' content moved to the
vault `README.md` (and its Korean twin), which survives being read; a guard test fails if anyone
re-adds a comment, because re-adding one would look like an improvement.

**And the door is unlocked.** `VaultDashboard.adopted` records the hash when a file's bytes already
equal what we would write. Without it the fix reached only vaults created after today: every
existing vault whose owner opened the dashboard **already holds today's shipped bytes** under a
ledger entry naming the old form. Claiming a file that *is* what we would write cannot cost an
edit — there is none, and writing would be a no-op. Unlike #308's ignore rules, there was a safe
automatic answer here, so it got one.

ADR: [[decisions/2026-08-14-the-seed-ships-in-the-form-its-reader-rewrites-it-to]], including the
cost that the fixed point is Obsidian's and a future version reformatting differently reopens this
with nothing to notice it.

---

## 2026-08-19 · #319 — the toolchain the workflow never declared

`gates (windows-latest)` failed once on #318: no `dotnet`, `CsharpRunnerExecutionTest` skipped 8,
the skip-guard fired. **I re-ran it and it passed** — and the comment above the `setup-node` pin,
written after the same thing happened twice with node (#174, #208), says exactly what that costs:

> rerunning until it passes trains us to rerun on a guard failure, which is how a guard stops
> meaning anything

The training it warns about, performed on the first guard failure after it was written. The guard
was right; what was missing was upstream of it.

**Measured which of the seven are declared** rather than assuming: `java`/`kotlin` ride the JDK
(Kotlin compiles through `kotlin-compiler-embeddable`, a build dependency), `javascript` had
`setup-node` — and `python3`, `gcc`/`clang`, `g++`/`clang++`, `dotnet` were all inherited from
whatever the image happened to ship. Four, not one.

- `setup-dotnet@v6` pinned to **8.0**, which is what `CsharpRunner` writes into the generated
  csproj — the pin follows the generator, not the image's default.
- `setup-python@v7` pinned to 3.13.
- No first-party action pins a C compiler, so a preflight step asserts the *pair* each suite
  accepts and names the missing binary at setup time instead of leaving a skip for a grep four
  steps later to infer. **Both paths run locally before committing** — pass, and a forced-missing
  run exiting 1.
- Action majors were checked against the registry, not recalled: my first guesses (`setup-dotnet@v5`,
  `setup-python@v6`) were both a major behind.

**And the job whose whole claim was weakened.** `repeatability` runs the same suites three times
with no skip-guard, so eight C# tests skipping on every attempt would satisfy *"reliably green
rather than green once"* exactly as well as eight passing ones. It had only the JDK. Same
declarations now — not a second guard, since `gates` also runs on ubuntu and would fail loudly.

`coverage` is deliberately left alone, with the reason written beside it: #283/#285 measured the
runner package identical with and without the execution suites, so a skip there cannot move the
number.

---

## 2026-08-19 · #323 — the file that outlived the records it named

Found while wiping the record repository, and it is the reason the wipe was checked rather than
assumed complete. After every record was gone, `ps-records.iml` — tracked and pushed — still read:

```
problems/120802-두-수의-합-구하기   problems/120803-두-수의-차-구하기
problems/120804-두-수의-곱-구하기   problems/181952-문자열-출력하기
```

**Two of those had been deleted in the earlier clean slate.** A repository reporting zero solved
problems was publishing a list of four.

`.idea/` (#304) cannot reach it: IntelliJ writes `<project>.iml` at the **root**. `*.iml` added
beside it, and a test pins that the two are independent — neither is an ancestor of the other, so
#307's ancestor check must not swallow the second when a reader has already written the first.

**Both new tests were run against the code with the rule removed** and both failed, then passed
with it. Green alone is consistent with a check that is not there — and this file's whole lesson
is that something can look absent and not be.

The rule reaches only repositories that have not tracked it yet, which is #304's own standing
decision (*an ignore rule has no effect on a tracked file, and a server running `git rm --cached`
has stopped being an ignore rule*). So `docs/bootstrap.md` gains the manual step, with the
`git ls-files '*.iml'` that finds it — and the Korean twin with it. The owner's own file is
removed by hand, in `ps-records`, not by the server.

---

## 2026-08-28 · #325 — the other shape of statement, unparsed until a stdin problem was solved

The owner solved lesson 181945 (문자열 돌리기) and asked what had been recorded. Everything was
there — PASS 3/3, both compile errors kept, `examples.json` carrying the raw newline inside quotes,
a generated runner that I ran and that printed `ALL PASS` — except the statement, which held raw
HTML: `<div class="highlight"><pre class="codehilite">…</pre></div>`.

**`pre` had a handler the whole time.** `div` did not, so the wrapper fell to the unknown-element
fallback — which preserves rather than drops, correctly — and took the `pre` inside it along. A
`div` is a container, not content; it recurses now.

**Two shapes of statement, and only one had ever been recorded.** A `solution`-function problem
states its worked examples as a `<table>`; a `main`-reads-stdin problem states them as code blocks.
Every problem in the vault was the first kind until this one.

`ProblemStatementPageTest` has asserted `shouldNotContain "<div"` since it was written. It passed
because the only fixture it ran against was the table kind — **the assertion was right and had
nothing to fail on.** Three new tests, all three verified failing with the fix removed.

**And a measured claim that was a claim about five problems.** The old fixture's header recorded
*"no nested `<div>` anywhere inside, which is what makes the region's end unambiguous"* as fact.
Corrected in place rather than deleted: it is a fact about those five, it was written as a fact
about the region, and jsoup never relied on it — the reader of that header did.

New fixture measured from the live page and scrubbed per §7.3: shape verbatim, every word invented.

Still open: 181945's own `statement.md` holds the broken text. `StatementBackfill` fills what is
**missing**, not what is wrong, so re-fetching it is a separate step.

---

## 2026-08-28 · #329 — an instruction that was true for the tool and not for its reader

The third thing lesson 181945 turned up, and the only one I found by **following the record's own
advice**. Every generated Java runner said:

```
// Run it with:  java RunnerTest.java  (Solution.java beside it)
```

On my shell — JDK 21 — that gives `cannot find symbol: variable Solution`, which points at the
generated file rather than at the JDK. On JDK 25 the same two files print `ALL PASS`. The
difference is JEP 458, which taught the launcher to resolve a second source file and shipped in
**JDK 22**.

**True for the tool, false for its reader.** The tracker requires 25 and CI runs 25, so nothing
was ever going to catch this. But the record repository is the user's own folder, opened with
whatever JDK they have — and Programmers offers Java 8 and 11 for many problems, so 11, 17 or 21
locally is the ordinary case.

`javac RunnerTest.java Solution.java && java RunnerTest` works on every JDK since 8, with the
one-liner kept as the shorter form where it applies. **Verified on the JDK 21 that failed**, not
only in a test.

Java only — checked all seven. The others either compile explicitly (`g++`, `gcc`, `kotlinc`) or
are interpreted (`node`, `python3`), so none depends on a launcher feature.

Two tests pin the header, for both shapes. Nothing had been watching it, which is how an
instruction drifts from what it instructs.
## 2026-08-28 · #327 — the column that said 188 hours about six minutes of work

The same record that produced #325. Its attempt history read `Elapsed | 188h05m31s`, and the
record behind it carried **both** numbers all along:

```
elapsedSec = 677131   (188h — wall clock since the page was first opened, eight days earlier)
focusedSec =    365   (6m05s — the tab actually visible)
```

**Not a data defect.** `elapsedSec` is exactly what #205 designed — calendar time from first
encounter — and its own KDoc says *"it is simply not the one the name suggests"*.

The defect is who was told. The MCP surface has carried the full warning since #287, with a
measured example, because that issue was about teaching a model to read these records. **The file a
person opens carried none of it** — which is #287 in reverse, and worse, because the reader has no
reason to doubt a column labelled `Elapsed`.

So the word goes, since the word is what does the damage: `Since opened | Focused`, side by side,
each named for what it is. Both land in the frontmatter too, which is what Obsidian's tables and
the MCP read.

**Absent, never zero.** The sensor extension is optional, so most records have no measurement —
and `0m00s` would claim the problem took no time at all, which is the failure
[[decisions/2026-08-10-sensor-observations]] already argued about this exact field. Two of the
four new tests are about that, and two existing assertions moved to the wider row, showing `-`
for a fixture that has no sensor.

Nothing changed about what `elapsedSec` measures. It is the right number for "how long has this
been hanging over me", which is what design §6.4's spacing wants — it was just the only one shown.

## 2026-09-29 — #331 a replaced credential reopens the observation (branch fix/331-reopen-on-credential-change)

**Measured, not reasoned.** Lesson 151136: subscription opened 09:54 under a dead cookie, file
replaced in place 09:58, `/watch` said `session: alive, subscription: live` from 10:00:40, a PASS
at 10:09:47 was not recorded, `docker restart` 10:12:01, a PASS at 10:12:46 was recorded. The
socket has no expiry signal ([[sources/2026-08-11-expiry-has-no-socket-signal]]) and therefore no
replacement signal either; nothing asked it which cookie it carried.

**What changed.** `SessionCookie.fingerprint()`; `CableChannelSubscriber` remembers the digest
each attempt connected with and `reauthenticate(channel)` reopens the job under `compute` when
the current digest differs; `WatchService.watch` asks on every heartbeat and, on a reopen, calls
`SessionHealth.credentialReplaced()` so the five-minute cache does not keep a dead `EXPIRED`.
Unreadable credential → no change; digest never logged; reopened → `PENDING`.

**Tests.** +3 subscriber, +1 watch service, +1 session health, +2 cookie. ADR
[[decisions/2026-09-29-a-replaced-credential-reopens-the-observation]].

**Review (reviewer agent) — two blocking findings, both taken.** B1: `renew` cancelled the old job
without waiting, so two `ChannelCapture` collectors could briefly share a channel; now one lock over
the three maps, the job is taken out first, `cancel` + bounded `join` (5 s) before the new one opens,
and a job no longer held never writes a fingerprint. Two tests pin it: the old observation closes
before the new one opens, and a reopen racing an unsubscribe leaves nothing running (25 rounds).
B2: the plan put merge before the live check — the exact pattern this issue is about. Reordered.

**Order now.** Gates → build the image from this branch → **live: cookie replaced in place with the
container running, observation reopened without a restart, badge green, next submit recorded** →
/commit → /pull-request → CI → merge.

**Live, 10:47 (image built from the branch, container never restarted).** Junk written in place →
same heartbeat: reopen logged, `session: expired`, `subscription: pending`. Original bytes restored
in place → next heartbeat: reopen logged, `session: alive`, `live` 3 s later. Remaining live item:
the owner's next submit is recorded on the reopened socket. Critic's adversarial pass on the
concurrency pending; then /commit → /pull-request → CI → merge.

**Live, 10:52:44 — the item the issue was opened for.** Lesson 133024's socket reopened through the
credential-change path (junk 10:51:19, original restored 10:51:20, both logged), then the owner's
submit was recorded through it: attempt 3, PASS 1/1, `session: alive`, `subscription: live`, no
restart. Earlier the same minute, a submit on a socket opened fresh after the container recreate
(133024 attempt 2, 10:48:24) was recorded too, which proves the fresh path but not the reopened one.

**Critic (adversarial pass) — five of five invariants attacked, two broken for real, one structurally.**
I2 broken 70/200: a replaced job wrote `health` under the channel name and the reopen read it as
"still wanted"; the race test ran serially and could not see it. Also: no rollback when a reopen is
interrupted (channel `PENDING` forever), an in-flight probe overwriting `credentialReplaced()`, the
cookie read twice per attempt, a blocking file read outside every timeout. Redesigned: one
`Observation` object per channel (job + health + digest), one map, one lock; the successor takes
the place first, the old job is cancelled and joined with a bounded patience, the successor starts
in `finally` unless stopped meanwhile; the cookie is read once and handed to the client; the file is
read on IO; `SessionHealth` carries a generation. Tests: race rewritten to actually race (emitting
flow, real dispatcher, "nothing collected after the stop"), interrupted reopen, patience expiry,
in-flight probe. Pre-existing registry single-writer assumption filed as its own issue.

**Critic, second pass on the redesign — I1–I7 all hold (520 rounds). Three behaviours fixed:**
F1 a reopen cancelled the old job past its own `connectionLost()`, so a grading it held open stayed
pinned in the registry un-evictably with no log (measured: `/watch` Saturated) — told now once the
close is confirmed, never on the patience path; F2 `runCatching` around the credential read swallowed
the caller's cancellation — rethrown; F3 cache invalidation was tied to "reopened" — `reauthenticate`
now answers `CredentialCheck(changed, reopened)` and the cache is forgotten on `changed`, once per
change rather than once per channel. Low/informational items recorded as accepted costs (torn read,
green badge during a reopen); probe dedup under concurrent heartbeats filed as its own issue.

## 2026-09-29 — #332 the sensor hands over the session (branch feat/332-extension-hands-over-cookie)

**Server.** `SessionState.MISSING` (unauthenticated; probe maps `MissingSessionException` → MISSING,
rethrows cancellation); `ManualFileSessionProvider.replace()` — atomic owner-only write beside the
file, equal value is `changed=false`, unwritable location serves from memory with `persisted=false`;
`POST /session` under the watch token, body read by hand, answer is a shape and never the value,
probe at once on a change. Error handler scoped to both sensor endpoints.

**Extension 0.2.0.** `cookies` + `https://school.programmers.co.kr/*`; `decide.js` (pure, tested
with `node --test`, run by `scripts/test.sh`); `background.js` pushes on `cookies.onChanged` and on
an `expired`/`missing` answer, once per value per worker, and re-sends the last heartbeat after a
change so #331 reopens within a second. Badge text for `missing`.

**Compose.** The cookie moves from the read-only secret mount to the writable `.ps/` directory —
same host file. Docs: SECURITY, extension README (+ko), bootstrap (+ko), README quickstart, ADR
[[decisions/2026-09-29-the-sensor-hands-over-the-session]].

**Live, pending.** Reload the unpacked extension (new permission), sign out and in on Programmers,
badge green within a heartbeat with no manual step, next submit recorded; restart with the browser
closed boots on the cached file.

**Review + critic on #332 — taken.** Reviewer: host permission must cover the cookie's own domain
(`*.programmers.co.kr`, else `cookies.onChanged` never fires — matches the silent re-login at 12:2x),
`.env.example`/troubleshooting still described `TRACKER_SESSION_FILE`, ADR outcome claimed what was
pending. Critic: JSON null/number/boolean written into the file as text (critical; strings only
now, cookie-octet shape, `;`/spaces refused); the "once per value" dedupe blocked recovery when the
server lost the cookie (removed — an equal value is free server-side); a failed persist was never
retried and shadowed a hand-pasted file forever (retry on the next hand-off, file wins once it
changes, `persisted:false` shown on the badge); `Files.exists` turned an unsearchable parent into
MISSING (absent vs unreadable now distinct); `contains('=')` misread base64 padding (decided by the
cookie's name); web origins now refused (403 FORBIDDEN_ORIGIN, extension origins admitted); a
heartbeat during a grading no longer reopens the socket (`mayReopen` from `registry.isGrading`).
The port-squatter exposure is stated as an accepted cost: a process running as the owner can read
`.ps/session` already. Live so far: trigger 2 at 12:33:49–50. Trigger 1 and the submit pending.

**Live, 2026-09-30 08:23 — trigger 1 measured.** Sign-out/sign-in with the widened host permission:
`handed over: changed=true, persisted=true` at 08:23:09.693 with no `expired` answer before it (the
change trigger, not the server asking); the open tab's channel reopened at 08:23:22; a submit at
08:23:36 recorded (133024 attempt 4) through a socket opened after the hand-off; restart at 08:24:36
booted on the cached file with zero hand-offs. Also measured: 103 hand-offs in 33 s, one with a new
value — a sign-in re-sets the same cookie on many responses. The change trigger now dedupes on the
last change seen (`decide.changeToPush`, tested); the server-asked path stays unconditional.
Next: /commit → /pull-request → CI → merge → rebuild from main.

## 2026-09-30 — #333 the registry has one lock (branch fix/333-registry-one-lock)
- Red on the old code, every run: 16 concurrent admissions into capacity 4 → 5 entries. A pin racing a
  refresh could be overwritten (get-then-put).
- `watched` → HashMap, every public method under one lock; KDoc's "one writer" sentence replaced.
- Tests: concurrent admissions (20 rounds), pin vs refresh (10 rounds); class 19/19 ×3.
  ADR [[decisions/2026-09-30-the-registry-has-one-lock]].

## 2026-09-30 — #334 one probe in flight (branch fix/334-one-probe-in-flight)
- Red on the old code: 8 concurrent callers on a cold cache → 8 probes. Now the first caller owns a
  Deferred, the rest await it; a probe that throws releases them and they ask again; the #331
  generation guard unchanged. Class 14/14 ×3, gates green. ADR [[decisions/2026-09-30-one-probe-in-flight]].

## 2026-09-30 — wiki ingest (#339, branch docs/339-ingest-session-expiry-sessions)
- Four raw sessions: 08-19 and 08-28 recovered from the inbox (two Wiki-Skip segments with no record),
  09-29 and 09-30 from the live context. Four source stubs, indexed; the four new ADRs and the two
  2026-08-11 expiry pages carry the raw sessions in `sources:` with ⚠️ follow-ups; the two concept
  pages gain the September cases and two counter-practice lines. Inbox emptied (the 09-22 sessionend
  snapshot was a cosal-judge check, out of this wiki's scope).

## 2026-10-01 — #341 a failed run that returned a result is WRONG (branch fix/341-failed-sql-run-is-wrong)
- Measured: three wrong SQL runs on lesson 131118 recorded `UNKNOWN`, badge `?`; the finish frame is
  `passed:false` + `returned_rows` + `msg:null` — the classifier had only ever matched a message.
- `TestcaseResult.returnedResult` (domain-named, nullable, default null); mapper sets it from the
  finish frame; resolver reads a null message as WRONG when a result was returned, unknown otherwise.
- Fixture `sql-run-wrong.jsonl` scrubbed (ids substituted, table columns kept, row invented); protocol
  §6 + §15 #17; tests at all three layers. ADR [[decisions/2026-10-01-a-failed-run-that-returned-a-result-is-wrong]].
- Pending: live — a wrong SQL run on the rebuilt container shows `✓ run WRONG 0/1`.
- ✅ Live 2026-10-03 15:23:52 KST, lesson 131537: wrong SQL run recorded `JUDGED · WRONG`, `returnedResult: true`.

## 2026-10-06 — wiki ingest (#344, branch docs/344-ingest-failed-sql-run-sessions)
- Three raw sessions (10-01, 10-03 from the inbox snapshot of `b240e44e`; 10-06 the finding made while
  ingesting) + source stubs, indexed.
- ADR 2026-10-01 Outcome: verified live; the SQL-error frame measured (MySQL error tuple as `msg`, no
  table → `UNKNOWN`, correct under the ADR; compile-error classification proposed, not decided, not
  yet in the protocol doc).
- Found while ingesting: every reader folds distinct gradings sharing a capture key — lesson 131537
  log 10 runs / 3 submits, `get_problem` 5 / 1. Filed #343 (not fixed). ⚠️ notes on ADRs 2026-08-11
  and 2026-08-05 (code-pending correction); concept `assumption-vs-measurement` gains the case.
- Inbox emptied: 22 sessionend snapshots of 2026-10-06 were handout-writing workers (out of scope).
- Open: #343 (needs an ADR — per-grading identity for the correction line).

## 2026-10-07 — design: mistake patterns from repair steps (#346, branch docs/346-mistake-pattern-design)
- Every MCP tool called against the live records; findings in the spec §2 (no run code, wrong SQL
  submit `실패` → UNKNOWN, SQL tag axis empty, #343 folding).
- Owner chose option A (diagnose on demand, MCP read-only). Spec
  `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md`; ADR
  [[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]].
- Next: implementation plan; build order 4.1 (#343 + two verdict gaps) → 4.2 runs.jsonl → 4.3
  repair_steps → 4.4 exam_prep.

## 2026-10-07 — #343 the history keeps every grading (branch fix/343-history-folds-identical-gradings)
- `RecordHistory` resolves per `(ts, captureKey)`; unit tests (three) + a store-level test + a production-path pin in `CodeAttachmentTest`; the history tests seen failing with the old key. Spec review ✅, quality review approved after one round (added the correction-after-a-later-identical-grading case and the production-path pin).
- ADR [[decisions/2026-10-07-a-record-is-its-time-and-its-bytes]].
- Measured for Part B (plan Part B0), 2026-10-07 09:28:19 KST, lesson 59034 attempt 2: an SQL submit MySQL rejects says `실패 (런타임 에러)`, not bare `실패` → Part B proceeds as written.
- Pending: live — `get_problem 131537` answers 3 submits / 10 runs after rebuild.

## 2026-10-07 — #349 database failures that said something (branch fix/349-database-failure-verdicts)
- #343 verified live: after rebuild from 4f1e660, `get_problem 131537` = 3 submits / 10 runs. ✅
- Bare `실패` (wrong SQL submit) → WRONG; MySQL error tuple on a run finish (returnedResult=false) → COMPILE_ERROR; a rejected SQL submit (`실패 (런타임 에러)`, measured on purpose on 59034) stays RUNTIME_ERROR — pinned.
- Fixtures sql-submit-wrong / sql-run-error / sql-submit-error; protocol §6, §7, §15 #18–20; ADR [[decisions/2026-10-07-database-failures-that-said-something]]. Spec ✅, quality approved after one round (fixture for the B0 measurement, tuple rule narrowed to the run path).
- Pending: live — a wrong SQL submit → WRONG, a syntax-error SQL run → COMPILE_ERROR on the rebuilt container.

## 2026-10-07 — #351 every run keeps its code (branch feat/351-every-run-keeps-its-code)
- #349 verified live on 59035 (10:06:17 run → COMPILE_ERROR, 10:06:55 submit → WRONG). ✅
- Measured the fetch race first: four fastest-possible alternating runs all got their own code (fetch 0.24–0.53 s after the record, fastest human gap 1.6 s).
- `problems/<id>/runs.jsonl`: recordId, language, codeFetchedAt, code; idempotent on complete lines. Review round: slimmed (verdicts stay in the log), codeFetchedAt for late attachments, torn-line retry fixed.
- ✅ Live 2026-10-07 10:51 on lesson 59036: three runs, three runs.jsonl lines with their own code.

## 2026-10-07 — #353 repair steps over MCP (branch feat/353-repair-steps-over-mcp)
- Measured before planning (live log, resolved per `(ts, captureKey)`): 131 gradings, 65 candidate steps, 25 starting at an unresolved grading, none submit→submit; 36 submits, every `codePath` readable; one `runs.jsonl` (lesson 59036, 3 lines) joined by `recordId`, none late.
- Built in 22 commits: diff into `domain/calc` (66589fc); problem counts per part/level (95fd6ae, c98d7e3); `CodeTimeline` + `RepairSteps` (6b23962, ec53a6a); `RepairStepFilter` (ec79875); `GradingCodes` + `FileGradingCodes` (8ad427d), kept code bounded by the real `problems/` (feec25a, 2cde57e, 4980e56); `RecordQuery.repairSteps`/`codedProblem` (feddcbd, e7ec79c); MCP `stats` part/level (7f844f2), `repair_steps` (be66122, e3f267e, 8665324), `get_problem(include)` (d547df2, 6ab8e19), instructions (5ae7223, 27cc839, 1545753, eccf93a). Spec and quality review per task.
- Found in review: Claude Code cuts MCP instructions and each tool description at 2,048 characters (CHANGELOG 2.1.84, 2.1.280; verified on 2.1.285) — the live instructions, 2,607 since #287, arrive cut. Budget now ≤ 2,000 as sent, pinned by tests: instructions 1,973 (27 left), get_problem 1,983, repair_steps 1,983, stats 1,788, others 691–1,356. Part 4.4 puts its guidance in the `exam_prep` prompt.
- Docs: `mcp.md` + twin (seven tools, reading a step, `include`, part/level counts, the code-read bound, `returned` not built), README and bootstrap tool counts + twins, spec §4.2/§4.3 (As built)/§6/§7, ADR [[decisions/2026-10-07-repair-steps-are-served-not-judged]], `codeLate` note on the mistake-patterns ADR.
- Filed: #354 (statement and run-log readers follow links out of `problems/`), #355 (internal fault answers id null / HTTP 500 in both eras), #356 (`incompleteHistory` after the payload).
- ✅ Live 2026-10-07 after rebuild from 193179d (one-correction case): repair_steps(59036) returns the WRONG→PASS run step with its diff; default call 20/65 truncated (12 KB); get_problem(include) and stats(part) as designed. Pending: the two-failing-runs case on the next real solve that has one.

## 2026-10-07 — #358 record the live verification of #351 and #353 (branch docs/358-record-live-verification)
- Both ADR outcomes now carry the live results; progress pending lines closed.

## 2026-10-07 — #354 no reader follows a link out of problems/ (branch fix/354-no-link-out-of-problems)
- Audit of every reader under `problems/` whose content leaves: the statement (`get_problem`) and run log (MCP), named by the issue, and the previous attempt behind `diffFromPrev` (log, served and pushed), named by its second comment before any code; the same statement inlined into the pushed README and `examples.json` (runner, pushed), not named — two readers the issue missed. `submitted` was already bounded by #353.
- `ProblemFiles` (`readAllBytes` / `readString`, mirroring `Files`): real path under the real root + `problems` by name, regular files only, never throws; `readString` strict as `Files.readString`. All six readers go through it; `RecordLayout` stays lexical.
- Tests: 14 helper unit tests + a planted-link test for each of the five newly bounded readers, each red against the old code (token returned, inlined, diffed; an outside run line; a runner from outside examples). Mutation: unbounding any of the six readers fails its test; dropping any helper check fails a helper test. Gates green (1,881 tests, `adapter/store` 85%).
- Code b70f242; `mcp.md` + twin; ADR [[decisions/2026-10-07-no-reader-follows-a-link-out-of-problems]].
- Found, not fixed, filed: #361 writers follow links (a linked README overwrote `.ps/git-credentials` in a scratch copy); #360 a linked `.gitignore` is not read by git, so `git add --all` staged `.ps/git-credentials` (git 2.48.1) — reconcile would commit and push the token.
- Pending: live — after rebuild, `get_problem`, `repair_steps` and the problem pages unchanged on the real records.

## 2026-10-07 — #354 review round (branch fix/354-no-link-out-of-problems)
- Two reviews of b70f242 + ab5b8f0: both approved with fixes — one Important (refusals were silent), the rest Minor, tests and docs.
- `b6048fb` refusals heard: `ProblemFiles` logs one WARN per refusal naming the candidate and `leads out of problems/`, `not a regular file` or the exception's simple class name — never content, an exception's message or a link's target; `NoSuchFileException` (missing file, dangling link) stays silent. Reworded: `CodeArtifacts` "Attempt N's code could not be read" (was "has no stored code"); the runner's empty-examples reason is the adapter's own "no examples could be read" (the generators say "were captured", untrue of a refused file).
- `edd068b` the checked real path opened with `NOFOLLOW_LINKS` (a last name swapped for a link mid-read fails; a directory swapped above it does not — accepted, the server never pulls); `readString` through a new decoder (REPORT), identical to `Files.readString` on ten edge inputs; `ProblemFiles` internal; KDoc notes a hard link passes.
- `a2cbb87` tests: `FileGradingCodesTest` 23 → 16 (one link-out test each for `submitted` and `runs`, on the fixtures, relative links); `madeFifo` into `PlantedLinkFixtures`; one `warningsWhile` in `support/logging`; `ProblemFilesTest` 14 → 17. Each new test seen red first except the silence pins and the out-and-back link, which pin existing behaviour.
- Mutation: each warning, the `NoSuchFileException` exemption, class-name-only, strict decoding, opening the candidate instead of the real path — killed; each of the six readers reverted to a direct `Files.read*` after the dedupe — killed; dropping `NOFOLLOW_LINKS` survives (race-only).
- Gates: 1,879 tests (8 skipped), ktlint, build, `verifyBranchCoverage` (`adapter/store` 85%, 408/480), guards.
- Docs: ADR — audit corrected (two readers unnamed, not three), #360/#361, the boot crash loop a throwing `readAttempt` caused (read from the code) and the diff now lost instead, the publication-boundary rationale (git stages a link as mode 120000 holding its path, measured), accepted costs (mid-read directory swap, hard link, macOS `Problems`, `<link>/..` root, statement refetch; the hard link, `Problems` and `<link>/..` measured, the rest read from the code); `mcp.md` + twin claim only the `problems/` bound.
- Re-review (one Important, Minors): `d04ec97` the test log capture returns what a layout writes — message, then throwable text — and refuses a level the logger is not enabled for; the re-reviewer's mutant (`failed()` also logging `cause`) survived before it and fails after. `af55d58` the backfill's work-list line says "with none readable". Docs: the `/watch` token is not in the records repository (`ProblemFiles` and `RecordLayout.recordFile` KDocs, ADR, `mcp.md` + twin); ADR `internal` is module-wide; the `<link>/..` exception noted (`submitted` ends silently as a missing file, measured). The same wrong claim elsewhere, left as it was: repair-steps ADR :41-42, `RecordLayoutTest` :155, plan `2026-10-07-repair-steps-over-mcp.md` :68, and the `b70f242` message.
- Pending: live — after rebuild, pages and answers unchanged on the real records, and no `Treating ... as absent` line on a normal boot.

## 2026-10-07 — #354 verified live (main a3838c0)
- Rebuilt at 23:03 KST, healthy in ~6 s. Boot log: 0 `Treating … as absent`, 0 WARN/ERROR; `Startup code attachment: AttachReport(attached=0, deferred=0, blocked=0)`.
- Every MCP read snapshotted before the merge (22:57) and after the deploy (23:03), legacy era, each answer's `structuredContent` with sorted keys: `submissions`; `stats` by verdict, language, problem, part and level; `list_problems(status=attempted)`; `review_queue`; `slow_passes`; `repair_steps(limit=1000)` (total 65); `get_problem(include=[code, runs])` for all 25 lessons. 36 files (35 answers + the derived lesson list), `diff -rq` identical. Record repository HEAD 7e144fa, clean, before and after. ✅ Closes the #354 live pending line.

## 2026-10-08 — #364 the exam_prep prompt (branch feat/364-exam-prep-prompt)
- Read before planning (plan b30129d): the MCP prompts pages and schemas for 2025-06-18, 2025-11-25 and 2026-07-28; Claude Code 2.1.285, read from the installed client — prompt arguments split on whitespace without quoting and filled by position, `title` and argument descriptions ignored, messages merged, prompt text not cut, only the user can run a prompt, and the modern codec shows no prompt without `ttlMs`/`cacheScope` on `prompts/list`. 38 of the catalog's 49 part names contain a space; 27 tracker connections on this machine, all modern.
- Built in 13 code commits: `ExamPrepScope` (b9210c3; review 8fd9a0b, ed3333b) — `since` checked with `Since`, a date in `language` refused as a positional slip, values JSON-quoted, the empty-answer line, the part-key warning; `ExamPrepPrompt` (92eb6da; review 1be2a09) — spec §4.4 steps 1–5 and the readings; `McpPromptCatalog` (84e22fd; review 285e371, ce4b06d, df2ccbc) — `language`, `since`, `part` in that order, one user message, `-32602` refusals, unknown keys quoted with the accepted list; `McpCall.name()` (94aa017) and the strict `promptArguments()` (7e9e924); routing in both eras with the `prompts` capability, modern `ttlMs`/`cacheScope` and `Mcp-Name` (7fc479b; review 2b6716e). Plan updates from review: 53b602a, fb515ea, 1663a0a, c9b0694.
- Tests: 46 new — `ExamPrepScopeTest` 12, `ExamPrepPromptTest` 7, `McpPromptCatalogTest` 13, `McpDispatcherTest` +11 (36), `McpCallTest` +2 (18), `McpControllerTest` +1 (21; the handshake opening and the modern discovery now also assert `prompts`). Full suite 1,925 (8 skipped, 0 failed).
- Review, per task: spec review passed Tasks 1–2 byte-identical to the plan. Tasks 1–2 quality review → quoted values, part keys not labels, the empty-answer line, refusals that name the order, step 1 spelled as calls, a pin that every argument and enum value the text passes exists in the tool schemas (8fd9a0b, 1be2a09); a follow-up review of the scope → ed3333b. Task 3: a mutation pass (285e371 — a `part` through the catalog; an array or object refused as "must be text", not thrown as a fault), then the quality review → a table test over all three names, quoted unknown keys (ce4b06d, df2ccbc), and the strict `arguments` reader moved to Task 4. Task 4 review → `NAMED` private, controller tests that pin what the wire carries (2b6716e).
- Docs: `mcp.md` (the prompt section; "genuinely absent" is resources only) and the README's MCP row (9295c0d), their twins (ba6113a); spec status 4.4; ADR [[decisions/2026-10-07-exam-prep-asks-in-the-open]], indexed and linked from the mistake-patterns ADR's outcome. KDoc decision links checked by hand: none dangling.
- Filed: #365 (five of the seven tools read a malformed `arguments` as none; every tool echoes unknown keys unquoted), #366 (a dangling KDoc decision link in `RecordQuery`; guards do not check `[[…]]`).
- Final review (whole branch) → one ordered `ExamPrepScope.ARGUMENTS` feeds the catalog listing, the unknown-argument message and the positional refusal (fd8a113); the docs name the command as Claude Code runs it, `/mcp__programmers-tracker__exam_prep`, since the menu's `/programmers-tracker:exam_prep (MCP)` is a display name that matches no command when typed without its `(MCP)` token (babc403, dc71c8c, ee6829e); the readings are in the instructions in brief, so the prompt carries the fuller wording (22e09ca, a15c4a3); ADR corrections — the form-client claim labelled an assumption, the step count stated exactly (9132ab9); spec §4.4 made historical and the package map says `MCP tools · prompts` (1f4b5e7). Full suite 1,927 (8 skipped, 0 failed).
- Pending: live — the rebuilt container answers modern `prompts/list` (`ttlMs`, `cacheScope: private`, `resultType: complete`) and `prompts/get`; then the owner reconnects the server with `/mcp` → Reconnect (a new session may still show the cached discovery: up to 900 s fresh, 4 h stale) and runs `/mcp__programmers-tracker__exam_prep` — or chooses it from the menu, which inserts that name — in Claude Code (the model cannot), and spec §6 accepts it when the answer names patterns that cite record ids.

## 2026-10-08 — #364 verified live, server side (main fb8b79b)
- Rebuilt; started 02:33:25 KST, healthy, 0 WARN/ERROR.
- Modern `2026-07-28`, headers mirrored (`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` on `prompts/get`): `server/discover` → capabilities `[prompts, tools]`, `resultType: complete`; `prompts/list` → `resultType: complete`, `ttlMs: 3600000`, `cacheScope: private`, `exam_prep` with `language`, `since`, `part`, none required; `prompts/get {language: "mysql"}` → one `user` message, 2,027 characters, with `repair_steps(language="mysql")` and the empty-answer line.
- Refusals: `Mcp-Name` mismatch → 400/-32020; unknown prompt → 400/-32602 "unknown prompt; this server exposes exam_prep"; `since: "SELECT"` → 400/-32602 `Since.FORMAT` + "; the arguments are positional — language, since, part"; `language: "2026-09-01"` → 400/-32602 "reads as a date"; `arguments: "java"` → 400/-32602 "arguments must be an object of strings".
- Legacy: `initialize` 2025-11-25 → `[prompts, tools]`; `prompts/get` → no `resultType`, one message, scope line "Scope: everything on record."
- Tool reads: the 36 snapshot files identical to the post-#354 snapshot; records 7e144fa, clean. ✅ server side.
- Pending: the owner reconnects with `/mcp` → Reconnect and runs `/mcp__programmers-tracker__exam_prep` in Claude Code (the model cannot); spec §6 accepts it when the answer names patterns that cite record ids.

## 2026-10-08 — wiki ingest (#368, branch docs/368-ingest-the-exam-prep-session)
- Session `b240e44e` sliced by KST from its transcript (the 139 MB inbox snapshot, ending 10-07 17:25, is a prefix of it): raw `2026-10-07-repairs-not-verdicts` (08:53–16:56), `2026-10-07-the-readers-that-followed-links` (16:56–23:59), `2026-10-08-the-prompt-only-the-owner-can-run` (00:00–02:36), each with a source stub; all indexed.
- New entity `claude-code-mcp-client`: what Claude Code 2.1.285 does with what the server sends, and how each fact was learned (changelog, code read, the client's own parser run, its own output).
- The seven 2026-10-07 ADRs cite the raws that hold their discussion (10-06 kept where the fold is the source, 10-03 added where the frame or the lost run code is); #354's outcome carries its live check, #364's the server-side check with the owner's run pending.
- Concepts: `assumption-vs-measurement` (the receiving end, corrections that overreached, two counter-practice lines); `orchestrated-implementation` (subagents: what review caught, interruption, a shared scratchpad, workers copying the brief's AI trailer — 117 of 180 commits on main).
- Inbox: the consumed snapshot `precompact-programmers-tracker-b240e44e.jsonl` deleted; `.inbox.log` kept.

## 2026-10-08 — #370 the first exam_prep run, and the judge's output where no diff exists (branch feat/370-judge-output-when-no-diff)
- **Acceptance (spec §6) met.** In Claude Code, after `/mcp` → Reconnect, the owner ran `/mcp__programmers-tracker__exam_prep mysql`.
  - The calls: `stats` by part and by level; `repair_steps`, truncated at 20 of 63, then `limit=63`; `list_problems(untouched, SELECT)`.
  - The answer: three patterns citing record ids, each with its problem count.
  - The first attempt had a leading space and was sent as text.
- **What the run showed.** 58 of 63 steps had no diff, because run code is kept only from 2026-10-07. Every pattern was named from MySQL's `failedMessage`. The five steps with code were measurement runs.
- **The change.** `ExamPrepPrompt` now says: where a step has no diff, name the pattern by the judge's own output (`errorText`, `failedMessage`) and say the diff is missing.
  - The new test failed first. adapter.mcp: 296 tests.
- **Docs.** `docs/mcp.md` and its twin; ADR outcome; raw `docs/llm-wiki/raw/sessions/2026-10-08-the-first-exam-prep-run.md` with its source stub; index; log.

## 2026-10-08 — #360 reconcile never stages .ps; what is committed and pushed is searched for tokens (branch fix/360-reconcile-never-stages-ps)
- **Round 1** (git 2.48.1, measured): git follows no `.gitignore` link at all (`PATTERN_NOFOLLOW`), so `add --all` staged `.ps/git-credentials`. The planned `:(exclude).ps` made `add --all` exit 1 in every healthy repository (`exclude_matches_pathspec`), so the pathspec is `[.]ps/**`. `0865d3c` pathspec + stdout-only dirtiness (git's stderr warning had made every reconcile an empty commit); `daa2f45` one WARN when git does not ignore `.ps/` (`check-ignore --no-index .ps/`); `d24d55b`, `9248472` the ignores writer neither reads nor writes through a link and no longer overwrites a non-UTF-8 file; `ce678cd` a failed status is not a clean tree.
- **Round 2, after an adversarial review**: five more routes to a pushed token in the same clone/pull model, all leaking on main too — F1 `.p`+U+017F folded to `.ps` by APFS, F2 a tracked link at the store, F3 `.ps` a tracked link (a plain `git pull` deletes the ignored directory to check it out; measured on 2.48.1 and 2.53.0), F4 `.PS`, F5 `push()` sending another tool's commit. Closed in layers: `5b70e90` content gate (`git grep -F -f -` over what is staged, and over `rev-list HEAD --not --remotes` before a push — the tracker has no upstream, so `@{u}` fails); `3c61615` `StateDirectory` (listed by exact name, not a link, real path — `toRealPath` alone returned `.ps` for an alias on Linux over a macOS mount, measured in the tracker's image); `0f1b2b5` credential written by temp + rename, helper only for a regular file; `6f43230` `icase`. Smaller: `feff707` state files owner-only again (keeping permissions is opt-in, `.gitignore` only); `e598336` `--untracked-files=all`, wait out a merge/cherry-pick/revert/rebase, no `.ps` path in a submit; `0cbd7fd` a read-only `.gitignore` is left alone; `09b01dd` WARN and KDocs no longer overclaim.
- **Round 3, after a quality review of `7ece5f7` (M1–M7, no Critical or Important) and a second adversarial review (N1–N11, R1, replace refs)**: `283893f` the `.ps` entry excluded as well — after the WARN's own advice a partial commit failed with `'.ps' does not have a commit checked out` (N3; exit 128 → 0 on 2.48.1 and 2.53.0); `5d0debc` no commit, push or credential while git tracks anything under `.ps` (`ls-files -z -- :(glob,icase)[.]ps :(glob,icase)[.]ps/**`, via the `TrackedState` port) or a link sits anywhere below it (N2: the server renamed the real token onto a tracked store; N2a; N1); `fcb07ea` every state writer asks `StateDirectory` first and skips with one WARN, `SeedLedger` writes by rename, `.ps` spelled once as `StateDirectory.NAME` (N1, N3b, N7 — reverses round 2's "the other writers keep writing"); `03896cb` `git push <remote> HEAD:refs/heads/<branch>`, remote from pushRemote → pushDefault → branch remote → origin, range `--remotes=<remote>`, an unborn HEAD is nothing to push (N4, N5, M2, M6); `db2e1a6` GitHub's token shapes searched beside the stored value (R1; `_` added for `ghs_<app id>_<JWT>`); `19ffde0` the working tree searched before `add` (M5); `32f3d4b` the submit path waits out a merge (N11); `5d9d41d` `GIT_NO_REPLACE_OBJECTS=1`; `8ed4fe6` the root listing injectable, so its mutant dies on macOS too (M4); `2dafd11` the KDoc and WARNs no longer overclaim (M1); `e71bb2a`, `03bd6d2` the nits (M7).
- **Round 4, after a third pair of reviews of `ea1357c` (adversarial: U1 High, ENV; quality: a Critical, Importants 1–3, Minors)**: `ea1357c` amended to `7f11f59`, its `Closes` made `Refs`; `2d3c09e` one `GitProcess` for every git call (CommandLineGitSync, TrackedStateEntries, GithubRemote, RecordRepositoryInit) without `GIT_DIR`/`GIT_WORK_TREE`/`GIT_COMMON_DIR`/`GIT_INDEX_FILE` and the four pathspec switches (ENV: `GIT_LITERAL_PATHSPECS=1` had made the tracked question answer "nothing"); `0590dac` U1 — every index entry judged by its first segment, `.ps` ignoring case as Java folds it (`.pſ` too) or the same directory on disk (`Files.isSameFile`): on APFS a pulled `.pſ/x` lands inside the real `.ps` and the ASCII-only `icase` pathspec saw nothing, so the server committed and pushed its own timers and frames and renamed the token onto a tracked file; `8de36cd` no walk (it raced the tracker's own temp+rename: 434/3,000 inspections refused, 2/500 sessions lost every frame here; the review 444 and 3), a stat per directory per frame instead, typed `Refusal` (transient never kept for a session), refused raw frames held in memory (8M chars; submit → attempt file, runs/orphans once usable, `close()` writes or says), `RecordWriter` says "No raw frames were kept", `AtomicStateFile.read()` never follows a link, one WARN per reason, `forGit()`/`forWriting()`/`tracksAnything()`, no fail-open default; `836c540` a grep that exits 1 after an `error:` (an unreadable blob — measured on 2.48.1 and 2.53.0: exit 1, "unable to read") is a search that did not finish, no search with no remote (`url`/`pushurl`), a head searched clean not searched again (keyed with the remote and a SHA-256 of the store), fail-closed pins (missing tree, bad remote ref, clean filter); `a8a184d` one `.ps` pathspec, KDoc corrected; `8f68977` a held orphan list copied under its lock; `49904f8` ktlint over the round's files.
- Tests: 1,928 → 2,012 across the first three rounds (9 skipped); round 3 added 39 and rewrote 5; round 4 → 2,043 (9 skipped). Each behavioural test was red first against the code before it; the new `StateDirectory`, `TrackedStateEntries` and guard tests were red as compile errors. Three pin what already held: near-miss tokens commit, the five names agree, a detached HEAD is not pushed. The first two die under mutants (token class widened to `gh[a-z]_` or cut to `{4,}`; a writer or the ignore rule naming another directory). The `.PS`/`.pſ` tests skip where the filesystem does not fold; `icase` needs `.PS` beside `.ps`, run on a case-sensitive APFS image (CommandLineGitSyncTest 61/0, StateDirectoryTest 13/0, 2 skipped each); `StateDirectoryTest` also ran in a Linux JDK 25 container on a macOS bind mount in round 2. CI has not run this branch.
- Mutation (round 2): content gate (commit, push), identity (git, credential), writer, helper, `icase` (on the case-sensitive image), F6, F7 ×2, F11 ×2, F9 ×2, F10, `StateDirectory`'s directory check — all caught. Survivors then: the realpath comparison (redundant on POSIX here), the listing on macOS (caught only in a Linux container over a macOS bind mount — round 3's seam kills it on macOS), the ignores writer's no-follow read and `CREATE_NEW` (race-only).
- Mutation (round 3, on the final code): entry exclusion 1 test, link walk 3, `ls-files` answer 7, state-file guard 4, raw-log guard 2, bare push 1, `--remotes` unnamed 1, unborn HEAD pushed 1, token shapes 3, pre-staging search 1, submit wait 1, replace refs 1, state check bypassed 8, listing 1 (macOS), `icase` 1 (case-sensitive image) — all caught. Survivor: the `--cached` search after staging (equivalent to the search before it except inside the window between them; kept).
- Mutation (round 4, on the final code; 32 mutants, all killed): tracked question ASCII-only 1, same-directory check 1, environment passed through 5, walk put back 2 (371/3,000 refused), transient refusal kept 1, raw directory checked once per log 1, frames skipped not held 7, no hold limit 1, silent `close()` 1, old `RecordWriter` message 1, read following a link 1, one WARN per file 1, no remote check 1, no head cache 1, head cache for any head 1, `error:` after exit 1 ignored 1, the three fail-closed refusals → `return true` 2/1/1, search after staging removed 1 (round 3's survivor, killed by the clean-filter test), and round 3's re-run: entry exclusion 1, nothing tracked 14, state-file guard 5, bare push 1, `--remotes` unnamed 1, unborn pushed 1, token shapes 7, pre-staging search 1, submit wait 1, replace refs 1, state check bypassed 9. Red for the store behaviours (implementation written before its tests this round) taken on 0590dac in a scratch worktree: 434/3,000 refused, 2/500 sessions lost, every frame lost after a passing refusal, `complete()` NoSuchFile while refused, a run and the first orphan never written, a frame written through a link swapped in mid-session, a read returning the link target, one WARN for two reasons.
- Measured: the first push's gate on the review's synthetic repositories, both searches — 1,660 commits / 4.1 MB 50.2 s (shapes 26.3 + stored 23.8), 5,000 / 9.6 MB 307.6 s (156.6 + 151.0, slowest call 9.3 s); the review's stored-only figures were 21.8 s and 139.6 s. A walk of 5,000 entries in `.ps` 14–17 ms, the `ls-files` question about 6 ms. Container (2.53.0) and host (2.48.1) agree on the entry pathspec, `ls-files` icase, the explicit refspec against `remote.origin.push` + `push.default=matching`, `--remotes` 0 vs `--remotes=origin` 1, `grep --untracked`, the ERE intervals, and replace refs (gate blind without `GIT_NO_REPLACE_OBJECTS`, push sends the original). Round 4: the review's race probes against the fix, 0/3,000 refused and 0/500 sessions short of a frame, three runs each; `ls-files -z` over 5,000 index entries ~9 ms; `git grep` over a commit with a missing blob exits 1 with `error: … unable to read` (2.48.1 and 2.53.0), a missing tree 128, `rev-list --not --remotes=origin` with a ref naming no object 128, a linked `.gitignore` only a `warning:`. U1 re-run with the critic's drivers on the round-4 boot jar, host and image: `Refused(TRACKED)`, no token stored, status clean, reconcile and push false, nothing of the server's in the remote.
- Gates, all exit 0 (round 4): check; test (2,043 JUnit, 0 failures, 9 skipped — 8 C# and the `icase` test on this case-insensitive host; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 85%, 225/262; `adapter/store` 84%, 535/632; `adapter/config` 65%, 25/38, at its floor; application 88%); guards. On the case-sensitive APFS image the git and store tests pass with the `icase` test running and 7 fold tests skipped. The round's commits before `49904f8` fail ktlint (its lint checks grepped a coloured report); the tree at the tip passes.
- Docs: ADR [[decisions/2026-10-08-reconcile-never-stages-the-state-directory]] rewritten for round 3 — five layers, every vector with its test, the options weighed (where `StateDirectory` lives, when writers ask, unknown answers), accepted costs with numbers, and the first draft's overclaims corrected ("four more routes" → five, F8 defined, "Git is not run", the Linux-CI claim, "the first dependency between adapters" — mcp→web exists since #46); `SECURITY.md`; dev rules §1 names the two adapter edges (git→store, mcp→web) and keeps `adapter/store` importing no other adapter; index; the state-beside-the-records amendment; the #354 ADR's pointer. Round 4: the ADR records the third reviews, six layers, the options weighed (how to ask what git tracks, the walk, where refused frames go, a push with nowhere to go), the vectors with their tests, the costs; `SECURITY.md`; the index; the state-beside-the-records amendment.
- Pending: live — after a rebuild and `up -d --force-recreate`, no refusal WARN at boot, the startup reconcile succeeds, and no `.ps` path in the next push. Not closed: CI has not run this branch (three OSes). Follow-ups the controller files: stale remote refs and the false alarm on already-published content; replay of a pulled raw session and the cached writable verdict; #373, #374, #375; blob-level scanning for the push gate, handle-based state writes (`SecureDirectoryStream`), commit and tag message scanning, the DailyBackup success record (#372).
- 2026-10-08 Windows CI on PR #379: test setup fixed (read-only git objects; autocrlf warning read as an object id), and a real `GitProcess` bug: its temp-file cleanup threw on Windows when receive-pack still held the output file, discarding git's answer — now best effort, pinned by `GitProcessTest`.

## 2026-10-08 — #362 coverage and the build gate no longer run the integration tests (branch chore/362-coverage-without-integration)
- **The defect, wider than the issue said.** Kover 0.9.9 instruments every `Test` task and makes its reports and `koverVerify` depend on all of them, and `check` runs `koverVerify`. `--task-graph` showed `:koverGenerateArtifactJvm` depending on `:integrationTest`; `--dry-run` listed it for `verifyBranchCoverage`, for `check`, and for `build -x test` — the build gate (`scripts/build.sh`, CI's build step), whose `-x test` drops `test` and nothing else. A scratch test tagged `integration` (it writes a marker file, no network) really ran under `verifyBranchCoverage` and under `build -x test`.
- **Latent today.** No `@Tag("integration")` test exists in the tree (`git grep` finds the tag only in docs and ADRs; `src` says "integration" once, in an unrelated comment), so the issue's "0 tests because no session file" is "0 tests because there are none". What did run: `:integrationTest` with Kover's agent attached (`build/tmp/integrationTest/kover-agent.args`, a 927 KB `build/kover/bin-reports/integrationTest.ic`). The first integration test added would have run inside the coverage and build gates.
- **The fix** (`767758f`): `kover.currentProject.instrumentation.disabledForTestTasks.add("integrationTest")`. From a clean build `integrationTest` is gone from all three graphs and Kover writes `test.ic` only. The Kover XML report is byte-identical before and after (802,154 bytes, BRANCH 3131 covered / 633 missed), and so are all 15 per-package lines of `verifyBranchCoverage`, both runs with `--rerun-tasks --no-build-cache`.
- **The pin** (`e3cebe2`): `gradle.taskGraph.whenReady` fails the build, before any task runs, if a task in the graph depends on `integrationTest`. Not the issue's "scheduled, unless named in `startParameter.taskNames`": a task named on the command line has no dependents, so naming it stays allowed (`integrationTest check`, `integrationTest verifyBranchCoverage` and the abbreviation `iT verifyBranchCoverage` pass) with no spelling of the name parsed, and a check on `verifyBranchCoverage`/`koverXmlReport` alone would have missed `check` and `build`.
  - Red, with the Kover line removed (`--dry-run`): `verifyBranchCoverage`, `koverXmlReport`, `koverXmlReport koverHtmlReport` (the CI coverage job), `build -x test`, `check` all fail with "would run :integrationTest because of :koverGenerateArtifactJvm"; `test`, `assemble` and `integrationTest` alone pass. `integrationTest verifyBranchCoverage` also fails, on purpose: a broken Kover setting pollutes the report whoever asked for the tests.
  - Green, with it restored: every one of those passes and schedules `integrationTest` only where it is named.
  - A real `verifyBranchCoverage` with the scratch test, Kover line removed: refused before the first task executes, no marker. With the fix: passes, no marker; `./gradlew integrationTest` makes the marker (uninstrumented).
  - A task renamed in its registration only: the script fails at `tasks.named`. Renamed there and in the check but not in the Kover line: the check fires on the new name.
  - Limit: `integrationTest` scheduled only as another task's finalizer is not a dependency and would not be seen. Kover 0.9.9 wires the tests as dependencies.
- **Found, not fixed (scope).** `verifyEveryTestClassRan` fails on any class tagged `integration` — "these test classes declare @Test but produced no results, so they never ran", measured with the scratch class — so the first real integration test will fail `./scripts/test.sh` and CI's test step. Not filed: `gh` cannot reach api.github.com from this machine.
- Docs: development-rules §6.5 gains one bullet. No ADR: the rule is §6.5 and [[decisions/2026-08-04-test-environment]] already; the last commit carries `Wiki-Skip`.
- Gates, all exit 0: check; test (2,044 JUnit across 160 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (numbers as above, `adapter/config` still 65% at its floor); guards.
- Pending: CI has not run this branch — the three-OS `gates` job (`build -x test`, `verifyBranchCoverage`) and the `coverage report` job (`koverXmlReport koverHtmlReport`) are the first runs of the pin outside this machine.
## 2026-10-08 — #355 a fault of ours answers its call (branch fix/355-internal-fault-keeps-id)
- **Decision.** A fault of ours (anything not an `McpFailure`) is answered by `McpDispatcher` to the call that met it: `-32603`, the request's id, HTTP 200 in both eras. The modern status was the open question. The 2026-07-28 binding (Streamable HTTP and the base page, read 2026-10-08) assigns statuses to named errors only and none to `-32603`, so the #353 test's "500 is what the binding assigns" had never been checked. Claude Code 2.1.285 reads a non-2xx as JSON-RPC only when it is a 400 that echoes the id. ADR [[decisions/2026-10-08-a-fault-of-ours-answers-its-call-on-200]].
- **Change** (`b9f2c8f`). `McpFailure.from(thrown)` is the one conversion: an `McpFailure` passes through, anything else is logged at ERROR by its class and becomes `internal()`, now on 200. The dispatcher answers with `call.id` and the era's status. The controller's catch-all is the backstop (`id: null`; nothing reaches it today) and no longer logs the whole throwable. A parse failure keeps `id: null` (400, `-32700`).
- **Tests.** `McpDispatcherTest` +3: both eras over `FailingGradingCodes(IllegalStateException(marker))`, and the ERROR line by class without the marker; red first, the exception escaped `dispatch`. `McpControllerTest`: the #353 tests assert the id, 200, `-32603` and no exception text, with a handshake-era twin added; red first, `expected:<41|42|43> but was:<null>`. The parse-failure test asserts `id: null`, a pin. adapter.mcp 296 → 300 tests.
- **Mutation** (8, all killed): a fault answered without the id 5, the old rethrow 6, `internal()` on 500 3 (the modern ones), 500 for a fault in both eras 5, the exception's message as the error 5, the throwable on the log line 1, no log line 1, a fabricated id for an unreadable one 1.
- **Docs** (`4599cd5`). `mcp.md` and its twin (the status and the id; the log by class); read-slice ADR outcome; repair-steps ADR (its 500 superseded, #355 done); the `claude-code-mcp-client` row; index.
- **Gates**, all exit 0: check; test (2,048 JUnit, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (adapter/mcp 92%, 338/366); guards.
- Pending: live. No real record breaks an invariant, so the running server cannot be made to fault on demand. That Claude Code reads a `-32603` on 200 as the answer to its call comes from reading its code, not from a run.
## 2026-10-08 — #366 a KDoc linked a decision that does not exist, and guards never checked (branch docs/366-decision-links-resolve)
- **The defect.** `RecordQuery`, `RecordQueryTest` and `SubmissionRecordFixtures` linked `decisions/2026-08-06-record-corrections-by-append`, a page no commit on any ref has ever touched. #58 (`153695d`, 2026-08-06) wrote the three links and a progress note with that slug and, in the same commit, filed the ADR as `2026-08-05-code-pending-correction-append`. That ADR is the decision meant: its Outcome tells the `RecordQuery` and object-mother stories the comments tell, it is the only decision about corrections by append, and the index lists it as that. `85dfab8` repoints the three links.
- **Nothing else under `src/` dangles**: 108 links to 24 pages. The issue's one-liner also prints `2026-08-05-write-serialization.md` and `2026-08-05-failure-taxonomy.md`; those are six comments that spell the target with `.md`, the pages exist, and the one-liner appends `.md` to a slug that already ends in it.
- **The guard** (`28a199c`, `scripts/guards.sh` §9). Every double-bracketed `kind/target` under `src/` resolves to a tracked page under `docs/llm-wiki/wiki/`. Any alphabetic kind, so a typo'd kind fails like a typo'd slug; `.md`, `|alias` and `#heading` are not part of the target; pages are read from the index and the text from the working tree, untracked files included. Canaries: `git grep` exiting above 1, no links found at all, and a planted link to a page that cannot exist must come back rejected. About 40 ms of a 3 s run (grep 21, ls-files 7, resolver at most 12).
- **Scope, measured: `src/` only.** Outside `src/`, `raw/` and `guards.sh`, 17 links fail. Ten are not claims: the schema's two examples, one placeholder, and seven in the vault's own namespace (`tags/…`, `problems/…`). Seven are real: the dead slug in a history entry here and in two wiki decisions, and four links to three `sources/2026-08-14-*` pages that the index lists and no commit has ever held. `raw/` carries the dead slug too and is immutable. §6's reason for leaving `[[…]]` alone holds for `docs/` and does not apply to code.
- **Verified.** Red: with the three pre-fix files restored the guard exits 1 and names exactly those three sites. Green: 108 links. A 15-link scratch file (7 that pass, 8 that fail) behaved as labelled, including a kind in the wrong case and `..` traversal. Three mutants — a resolver that accepts everything, a pathspec that matches nothing, a `git grep` that exits 128 — each tripped their own message. The resolver prints the same lines under macOS awk and Ubuntu mawk 1.3.4, and the whole script passes in an Ubuntu 26.04 container (git 2.53.0) on a fresh single-commit copy of the tree.
- **Docs.** ADR `2026-08-10-guards-must-prove-they-ran`, "Applied again 2026-10-08 (#366)": how the slug came to exist, the scope decision with its counts, the grammar, and what is left unchecked. `guards.sh` §6's comment points at §9. No new ADR: this is the same canary decision applied once more, as #172 did.
- **Left for the owner.** (1) The seven real broken links, which is `/wiki-lint`'s work; the three source pages were never in any commit, though the index lists them. (2) `build.gradle.kts:135` is the one code file outside `src/` that carries a wiki link; it resolves and is not scanned (one word in the pathspec). (3) The six `.md`-suffixed comments, if the canonical spelling is wanted.
- Gates, all exit 0: check; test (2,044 JUnit in 160 classes, 0 failures, 9 skipped, `verifyEveryTestClassRan`; node 4/4); build; guards.
- Pending: CI has not run this branch; the Ubuntu container run stands in for its guards job.

## 2026-10-08 — #356 incompleteHistory leads every answer (branch fix/356-history-warning-first)
- **The defect.** `McpToolInvoker.withGaps` appended the warning with `payload + (…)`, so it was the last key of every tool's answer and the last bytes of its text. A client that cuts a large answer cuts its end — Claude Code caps a tool result at 25,000 tokens by default — and the cut removed exactly the warning that says the counts have holes. Found in #353's quality review; pre-existing, all seven tools.
- **The fix.** `JsonObject(mapOf("incompleteHistory" to …) + payload)` at the one wrap point every tool passes through, so there is no per-tool code. `structuredContent` and the text copy are the same object serialized, so one order serves both; `repair_steps` keeps `count`, `total`, `truncated`, `steps` behind it.
- **Tests** (`McpToolInvokerTest` 90 → 93). A table over all seven tools checks the first key of `structuredContent` and that the text opens with the whole warning, naming each tool that fails; a second pins the `repair_steps` envelope, cut and whole; a third fails when a tool joins the server without joining the table. The existing every-tool test now shares the table and the hole fixture.
  - Red on the old code: all seven tools led with their payload key (`count` for `submissions`, `review_queue`, `slow_passes`, `list_problems` and `repair_steps`; `lessonId` for `get_problem`; `groupBy` for `stats`), and `repair_steps` answered `[count, total, truncated, steps, incompleteHistory]`.
  - Mutation, each reverted: the key back at the end — both tests fail; the text copy only — the text check fails alone, naming all seven; `repair_steps` steps ahead of its counts — the envelope test fails alone.
- **Measured, probe not kept.** A `repair_steps` over 29 steps of 300-line run code (20 returned) is 657,052 characters of text. In the legacy and the modern era it opens `{"incompleteHistory":{"lessonsWithOrphanedFrames":1,"frames":1,"lessons":[120802]},"count":20,…`, and the first 1,000 characters carry the warning. On the wire the text copy comes before `structuredContent`, so a client that keeps the head keeps the text.
- Docs: `mcp.md` and its twin say where the key sits and why (the twin's `translated-from` is the blob of the staged English page). No ADR: the direction is the one the repair-steps ADR filed under #356, and the last commit carries `Wiki-Skip`; that ADR's #356 line is left for the session ingest.
- Accepted: JSON key order is not a contract. A client that parses the answer and re-sorts its keys loses the lead position; nothing on the server's side can prevent that.
- Rebased onto main 6a46c77 (#355) at the end: `McpToolInvoker` and the tests merged clean (their change there is one comment line). One conflict, the twin's `translated-from` line, which both branches had moved: it now names the blob of the merged English page, and guards check it. `progress.md` is `merge=union`, so both entries stayed.
- Gates, all exit 0 on the rebased tree: check; test (2,051 JUnit across 160 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (`adapter/mcp` 92%, 338/366; `adapter/config` still 65% at its floor); guards.
- Pending: live — not observable on the author's records: the last live look (2026-10-08, the first `exam_prep` run) saw no `incompleteHistory`, so there is no answer in which the order shows. It appears only where a hole exists.

## 2026-10-08 — #372 a backup counts only once its records are committed; a directory git cannot open is said (branch fix/372-backup-needs-reconcile)
- **The defects, from #360's adversarial review.** `DailyBackup` never read `reconcile()`'s answer, so a day whose reconciliation the content gate refused, that waited out the user's merge, or that an index lock never let through was recorded as backed up once the push landed, and the next check found nothing due. And since #360 dirtiness is read from stdout alone, so a directory git cannot open — reported on stderr only — was skipped without a word (F8).
- **`4bfcc35` the backup.** A day is recorded only when `reconcile()` and `push()` both answer true. The `GitSync` port keeps its boolean: "nothing to reconcile" already answered true and every failure false, so no caller needs an outcome type; the KDoc now says what each answer means. The push still runs when reconciliation failed, so what is committed leaves the machine while the day stays due. A branch with no commit yet and nothing to commit counts — nothing exists to back up, and #360 made that push answer "nothing to push"; one whose reconciliation was refused does not. A held day is said once for each scheduled backup (the check runs every minute).
- **`beaa494` git answers in English.** `GitProcess` pins `LC_ALL=C`. Measured with Homebrew git 2.48.1 here: under `ko_KR.UTF-8` the could-not-open line came back translated (prefix still `warning:`); under `de_DE.UTF-8` the prefixes too — `Warnung:`, and a grep over an unreadable blob said `Fehler:` and exited 1, which the content gate's `error:` check read as nothing found. `LANGUAGE` is ignored in the C locale (the test inherits it).
- **`835f4dd` F8 said.** Every status's stderr is read for `warning: could not open directory '<path>': <reason>`; each directory is named once per instance, with git's reason, never its content. A warning, not a failure: reconciliation commits the rest and answers true (read as a change, as before #360, it failed every reconciliation — and would now hold the backup at every check). `unable to access '<path>'` is left alone: it names a `.gitignore` git could not read, whose rules then do not apply.
- **Measured** (git 2.48.1, `LC_ALL=C`): status exits 0 with stdout empty and the line on stderr for a directory at `000` and `-wx`; at `r--` it says `unable to access '<dir>/.gitignore'`, the line for its subdirectory, and lists a file directly inside (which the gate's grep then fails on, loudly); a tracked directory at `000` hides a changed file and gets the line; at `r--` only `<path>: Permission denied` — not caught. A linked `.gitignore` gives `unable to access '.gitignore': Too many levels of symbolic links`. `add --all` and the partial commit go through with only the warnings.
- **Tests.** `DailyBackupTest` +7: the token search's refusal, a merge in progress, a lock that never clears, a branch with no commit yet whose reconciliation was refused, what is committed still going up, the held day said once per scheduled backup — each red first (`expected:<false> but was:<true>`); the no-commit-yet day that counts is a pin, green before. `GitProcessTest` +1, red first (it answered in Korean). `CommandLineGitSyncTest` +2 (chmod 000 via `sealedWhile`, which gives the permissions back for `@TempDir`; skipped without POSIX permissions and under a superuser), red first (`List is empty`).
- **Mutation** (9, all killed, each reverted): reconcile's answer ignored 6 tests; the push skipped while held 1; the held line at every check 1, or once per process 1; a push with no commit yet answering false 1; the `LC_ALL` pin removed 2; the detector removed 2; a directory said at every status 1; the warning read as a change 1.
- **Gates**, all exit 0: check; test (2,061 JUnit across 160 classes, 0 failures, 9 skipped — 8 C# and the `icase` test on this case-insensitive host; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 86%, 229/266; `application` 88%, 344/387; `adapter/config` still 65% at its floor); guards.
- **Docs.** The 2026-08-06 backup ADR gets a dated amendment to decision 4 (the rule, what was weighed, the costs); the #360 ADR's outcome records F8 said, the `LC_ALL` pin and the unborn push, and its F8 cost points there.
- **Found, not fixed.** A repository with no remote — documented as supported — never records a backup once it has a commit, because its push answers false; from the first scheduled backup after that, every check (once a minute) logs `git push skipped … no remote named origin has a URL` and `Daily backup could not push`, for as long as the server runs. Read from the code (`BackupSchedule` every `PT1M`, `DailyBackup.incomplete()`), not run; pre-existing, before #360 the first line was a `git push failed`. Not filed: `gh` cannot reach api.github.com.
- Pending: CI has not run this branch (Linux and Windows: the chmod tests skip on Windows; the locale tests pass trivially where git has no translations). Not verified live.
- **Review of PR #389** (approved, nothing blocking; commits on top of `dcc5ba0`, no rebase):
  - `31b6264` the pin's second reason, pinned. In a UTF-8 locale macOS's regex stops at a byte that is not UTF-8, so `git grep -E` missed a token after one on the same line: `caf`, `0xE9`, a space and a token-shaped string gave exit 1 and no stderr under `en_US.UTF-8`, 0 under `C` (Homebrew 2.48.1 here; the review saw it with Apple's git, and no difference on glibc). The token before the byte or on the next line is found either way, and so is the stored value (fixed strings). Test through the real `reconcile()` with `en_US.UTF-8` inherited: red with the pin removed (`expected:<false> but was:<true>`); on Linux it passes either way, as its KDoc says.
  - `f52956f` two surviving F8 mutants killed: a dedupe key shared by every directory and a pattern that stops at a quote. The test seals `it's/` and a directory with a space and Hangul; each mutant fails it (expected 2, was 1). Git prints `'it's/'` raw (measured).
  - `af5ed81` F8 is said once a day while it lasts, by the date on the process clock (`GitConfiguration` passes it), not once per process; the review measured every day recorded around a directory at `000` with one warning on the first day. Decided: the day is still recorded (holding it retries every minute for what only the owner can fix); the exception is stated in `GitSync` (`reconcile`, `commitSubmission`) and `DailyBackup.performed`, and pinned at both layers, red first (expected 2, was 1). Also: named groups, nothing added inside a filter, the map's bound justified in its KDoc, and the warning no longer says nothing under a tracked directory is pushed.
  - `78e1908` the held-back line no longer says "the push landed" (false on an unborn branch) and says "this process says so once for each scheduled backup".
  - `dd3383a` `GitWorkspace.mergeLeftInConflict` within ten lines.
  - Docs: the #360 ADR's layers 5 and 6 corrected, the pin's two reasons, an accepted cost for the records repository's hooks under `LC_ALL=C` (measured: bash `${#x}` counts bytes, 6 against 2 for a two-syllable Hangul word; Ruby's default encoding US-ASCII; Python 3 unchanged; no `git.mo` in the tracker's image), and the review's paragraph. The wire-git ADR's decision 4 points at its amendment, which names the per-minute repeats of `CREDENTIAL_FOUND` and a failing pre-commit hook while a day is held, left to #390.
  - Mutation, this round, all killed: the pin removed 3 tests; a constant dedupe key 1; a quote-refusing pattern 1; the date ignored 2; the dedupe removed 3; the directory holding the day 4.
  - Gates, all exit 0: check; test (2,065 JUnit across 160 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 85%, 232/272; `application` 88%, 344/387; `adapter/config` 65% at its floor); guards.
## 2026-10-08 — #381 the test-class check leaves out classes tagged integration (branch fix/381-integration-classes-counted)
- **The defect, reproduced.** #362 found it and left it (scope): `verifyEveryTestClassRan` demanded a result file from every source declaring `@Test`, and `test` excludes `@Tag("integration")`. With a scratch class tagged `integration` that does nothing and touches no network, `./scripts/test.sh` on the branch's base: `:test` passed, `:verifyEveryTestClassRan` failed with "these test classes declare @Test but produced no results, so they never ran: ScratchIntegrationTest", exit 1; 160 result files, none for it. Latent, as in #362: no integration test is in the tree, so the first real one would have failed `scripts/test.sh` and CI's test step.
- **The case the check exists for, measured on the same code.** An untagged class whose only `@Test` is `fun neverRuns() = 1 + 1`, which returns Int: JUnit 6.0.3 says nothing, `:test` passes, no result file. The old check named it and the tagged class alike, so a fix that only silenced the message would have lost it.
- **The fix** (`38af9e7`). `testClassesOwingAResult` owes a result to a source that has `@Test` and no line beginning `@Tag("integration")` unindented, read from the source the way `@Test` already was. Left out rather than counted from `integrationTest`'s results: that task runs only when named, so a verdict reading its results would change with the invocation. The anchor is deliberate. A mention in a comment, or the tag on one method or nested class, leaves nothing out, so a class that `test` runs in part still owes a result. A class tagged only on its methods fails the check, loudly, and the message says to tag the class. A false alarm is the safe way to be wrong here; a class nobody checks is not.
- **The pin** (`09c0bba`). The build has no harness for its own tasks, so `proveTestClassFilter()` runs first in the task: a class tagged `integration` must be left out and one tagged only on a method kept, or the task fails saying its verdict would mean nothing (the canary of [[decisions/2026-08-10-guards-must-prove-they-ran]]). Four mutants of the filter each fail with that message: tag ignored (kept `[LiveTest, MixedTest]`), pattern unanchored (`[]`), a pattern that never matches (`[LiveTest, MixedTest]`), one that matches every source (`[]`).
- **End to end**, final build file, `./scripts/test.sh`, scratch classes removed after each run: a tagged class alone exit 0, and the same with CRLF line endings (Windows runners check out with `core.autocrlf=true`); the untagged Int-returning class alone exit 1 naming it; both together exit 1 naming only the untagged one; a class tagged only on its method exit 1 naming it; an untagged class that mentions the tag in a comment exit 1 naming it. The tagged scratch class does run under `./gradlew integrationTest`, with its own result file, so leaving it out of this check hides nothing that runs nowhere. With no integration class, `integrationTest` succeeds with no results.
- **Limits.** A source scan, like the check it extends: a line starting `@Tag("integration")` at column 0 inside a block comment or a raw string would leave its class out, and an inherited or composed tag is not seen, so that class fails loudly. Nothing here checks an integration class's own tests, which are run by name, by hand.
- Docs: development-rules §6.5 gains one bullet. No ADR: the pattern is the guards ADR's, and the choice and its cost are in the task's comment; the last commit carries `Wiki-Skip`.
- Gates, all exit 0 from `clean`, on a tree rebased onto #384: check; test (2,051 JUnit in 160 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (every package at or above its floor, `adapter/config` 65% at its own); guards (12 checks).
- Pending: CI has not run this branch. The three-OS `gates` job is the first run of the check on Windows checkouts; here that case was measured with a scratch class converted to CRLF.
## 2026-10-08 — #365 the tool path reads arguments strictly and quotes unknown keys (branch fix/365-strict-tool-arguments)
- **The defects, reproduced on the dispatcher before any change.** `McpCall.arguments()` read anything but a JSON object as `{}`. `"arguments": "x"`, `[]` or `5` made `submissions`, `list_problems`, `review_queue`, `slow_passes` and `repair_steps` answer over everything on record (`submissions`: `count` 3 of 3), in both eras, and made `get_problem` and `stats` answer "lessonId is required" / "groupBy is required". `McpToolInvoker.checked` joined unknown keys raw: `unknown argument(s): a, b`, and a key `"a\nb"` put a raw newline into the message.
- **Change.** `0d8ea58` renames `promptArguments()` to `strictArguments()`. `adc0d0c`: `tools/call` reads through it in both eras; a non-object is `-32602` "arguments must be an object", on 400 modern and 200 handshake, as an unknown tool is; absent or `null` is none; the lenient `arguments()` lost its last caller and is deleted; `stringArgument` reads through the strict reader; the prompt's refusal loses "of strings", which was untrue of a tool. `8e69e5e`: `McpArguments.unknown` quotes each key as a JSON string and names what the owner takes in the order given ("takes no arguments" for none); `McpToolCatalog.argumentsOf` reads each tool's list from its own schema, so the invoker's seven `*_ARGS` sets are gone; the prompt's copy of the message goes (it appends ", in that order"). `6be93aa` (the issue's optional item): `McpArguments.optionalText` is the one text-argument reader for `repair_steps` and the prompt. `3b44f3a`: `mcp.md` and its twin.
- **Error kind, checked against the spec and the code.** The CallToolRequest schema types `arguments` as an optional object in 2025-11-25 and 2026-07-28 (spec repository at `0a11bf68c7`), and the tools page makes a request failing it a protocol error and input validation a tool error. Unknown keys and bad values were and stay `isError`: `checked` and every value check throw `IllegalArgumentException` inside `executed`.
- **Tests** (adapter.mcp 303 → 317). `McpDispatcherTest` 39 → 41: every tool × a string, an array, a number and a boolean × both eras, pinning status, code and message; absent and `null` on `submissions` in both eras (a pin); the prompt's non-object test now pins the message. `McpCallTest` 18 → 19: `stringArgument` through the strict reader; the non-object test pins four shapes, 400 and the message. `McpToolInvokerTest` 93 → 95: `"a, b"` and `"a\nb"` as exact text; every tool's list in schema order (`get_problem takes lessonId, include`, `list_problems takes level, part, tag, status`; sorted they would differ). `McpToolCatalogTest` 26 → 28: `argumentsOf` is the schema's properties in order; a schema without `properties` takes none. `McpArgumentsTest` 7, new.
  - Red first: the dispatcher table failed at `submissions with arguments "x"` (`expected error … but actual was null`); `stringArgument` threw nothing; both message pins showed "of strings"; the invoker tests expected `unknown argument(s): "a, b"; stats takes groupBy` but got `unknown argument(s): a, b`; `McpArgumentsTest` and `argumentsOf` did not compile before their code. Two exceptions: the absent/`null` pin held already, and the schema-without-`properties` test was written after its code; mutants showed both red.
- **Mutation** (24, each reverted, all killed). Step 2 (8): lenient reading in the modern era, in the handshake era, the old message, `null` refused, absent refused, a lenient `stringArgument`, the refusal on 200, another code. Step 3 (10): keys unquoted, no list, the list sorted, no "takes no arguments", keys in arrival order, the owner left out, `argumentsOf` sorted, `properties` required, unknown keys let through, the invoker naming another list than it checks. Step 4 (6): `null` read as a value, the reader trimming, a number read as its digits, the refusal reworded, the prompt not trimming, the prompt keeping a blank.
- **Wiki.** The exam_prep ADR's Outcome records #365; its D4 line and its accepted cost are marked superseded and closed. No new ADR: this applies the decision that ADR made for the prompt.
- Branched from main 4f77194 (#384, fast-forwarded before the first commit) and rebased onto dce9423 (#388) at the end: no conflict, `progress.md` merged by `union`. The hashes above are the rebased ones.
- Gates, all exit 0 on the rebased tree: check; test (2,065 JUnit across 161 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (`adapter/mcp` 92%, 334/362, the same 28 uncovered as before); guards.
- **Review of PR #393** (approved, five non-blocking points), fixed in commits on top of the pushed head `2aef317`, without a rebase.
  - N1 `67760e9`: nothing pinned that the arguments refusal comes before the tool lookup. The dispatcher's table names only tools that exist, and two mutants that let the unknown-tool refusal win, one per era, survived all 317 MCP tests. A pin now sends `exam_start` with `"arguments": "x"` in both eras. It passed on the code as it stood, and both mutants die on it: their answer had the same code and status, with `unknown tool; this server exposes …` as the message. The order is the reference TypeScript SDK's (1.32.1: `setRequestHandler` parses against the schema before the handler looks the tool up, `src/shared/protocol.ts:1461`, `src/server/mcp.ts:226`; read rather than run, and it answers that parse failure with `-32603`).
  - N2 `1639a44`: `McpCall.stringArgument`, a third text reader with no caller outside its tests since #46, is deleted with its test. The two tests that also used it assert on `strictArguments()`.
  - N3 `979777c`: `McpArguments.unknown` is renamed `unknownArgumentsMessage`; the invoker had passed it a local also called `unknown`. Red first: the tests, pointed at the new name, did not compile.
  - N3, N4 `a27f1c3`: the comment above `McpCall`'s readers describes the two that remain instead of a lenient sibling that is gone, and `strictArguments`' KDoc says why a JSON `null` is read as none although it fails the schema.
  - N5: the ADR names #364 as the issue that added the strict reader, and records the order and the SDK's.
  - Gates, all exit 0 after the review: check; test (2,065 JUnit across 161 classes, 0 failures, 9 skipped; node 4/4; adapter.mcp still 317, `McpDispatcherTest` 41 → 42 and `McpCallTest` 19 → 18); build; `verifyBranchCoverage` (`adapter/mcp` 92%, 330/358, still 28 uncovered: the four branches of `stringArgument` went with it); guards.
- Pending: live. The running container predates the branch; after a rebuild, `tools/call` `submissions` with `"arguments": "x"` should answer `-32602`, and `stats` with `{"groupBy":"verdict","a, b":1}` should quote the key.
## 2026-10-08 — #385 the wiki's dead links: three source pages written, two decision links repointed (branch docs/385-wiki-dead-links)
- **The defect.** Six `[[…]]` links under `docs/` resolved to no page. `index.md:116–118` and `concepts/assumption-vs-measurement.md:324` linked three `sources/2026-08-14-*` pages no commit had held: #313 and #321 wrote the raw sessions and the index lines, and never the pages. `decisions/2026-08-08-run-raw-sessions.md:113` and `decisions/2026-08-11-a-hole-in-the-record-is-reported-not-filled.md:47` linked `decisions/2026-08-06-record-corrections-by-append`, the slug #366 repointed under `src/`.
- **Pages** (`255c916`, `ee14be3`, `a167321`): `the-warnings-and-what-was-under-them`, `the-clean-slate`, `the-first-run-test-and-what-it-found`, each written from its raw session alone — summary, key claims linking the decisions and concepts they touch, what turned out wrong. Measured and asserted are marked as the raw marks them; every link resolves to a tracked page; `raw/` untouched. `0842267` moves "six PRs merged" to the day the raw counts it for (#311, #312, #313, #315, #317, #318 on main).
- **Links** (`df29ffb`): both decisions now link `decisions/2026-08-05-code-pending-correction-append`. Neither sentence names the slug in prose; `updated:` bumped on both. The last commit repairs the seventh the same way: this file's 2026-08-06 entry for #36 linked the ADR by the name it was proposed under, and now links the filed page and keeps the proposed name as text; the guards ADR's outcome and §9's comment say all seven are repaired.
- **Transcript check, before the concept was touched.** None of the 23 older transcripts in the project's directory is from 2026-08-14: all are 2026-10-06 sessions (07:13–13:22 UTC), each matches `#316` exactly once, in this file's `2026-08-14 · #316` heading injected at session start, and none holds an August timestamp. `gh` could not reach GitHub for the PR body. The evidence was the day's own records: the raw sessions, the ADRs, this file's 2026-08-14 entries and the merge commits.
- **Disagreements, corrected.** `9fe4763`: `index.md:118` names the two defects instead of "only a first run could surface", which came from f17296f's message. `012b2c4`: the concept keeps the owner's approval of the moved push marked ⚠️ (unverified), since no record mentions it; "cited approvingly one message earlier" becomes the records' account, a different option (one commit scope) just turned down on the same sentence; "the afternoon" stays, as the segment's PRs merged 14:34–16:51 KST; "added two" gives way to naming #302's estimate, the `codePending` double write and the clean-slate runner tables. `b59dde3`: the guards ADR's #366 section gains an outcome and §9's comment its count — six repaired, and the seventh is this file's 2026-08-06 entry for #36, not the exam-prep plan's grep pattern, which was always one of the ten that are not claims. `ac3a994`: the push ADR cites the first-run raw instead of the warnings raw; the seed ADR cites the first-run and clean-slate raws and keeps the warnings raw for the #308 contrast.
- **Left alone.** The schema's two examples, the vault's own links in the tag-map plan and spec, the exam-prep plan's ellipsis placeholder. The dead slug in `raw/sessions/2026-08-11-expiry-has-no-socket-signal.md` (immutable). `SeedLedger` (#300) has no ADR of its own (out of scope).
- **Verified.** The issue's lint, run as separate `git ls-files` and `git grep` calls with the same resolution, prints only the left-alone links (10). §9's resolution over every tracked file outside `src/`, `raw/` and `guards.sh` fails 10: exactly its ten that are not claims (one of them a vault tag link in this file, which the docs-only lint does not read). Gates, both exit 0: guards (221 source citations, two more than before `ac3a994`; 111 links under `src/`); check.
- Pending: not pushed; CI has not run this branch; main has moved (#384 also appends to this file), so the branch is rebased before its PR.
## 2026-10-08 — #361 no writer follows a link (branch fix/361-writers-never-follow-links)
- **Audit first.** Every write, create, move and delete in `src/main` was grepped and classified by
  path, by what a link at the file or on the way could redirect, and by what it writes. The issue named
  six writers. Eight more were found: the code files and the raw copy (a link on the way, which the
  rename and `CREATE_NEW` never covered), `log/submissions.jsonl`, the tag notes, the vault seeds, the
  heartbeat marker (at the root when `.git` is not a directory), and the runner sweep, which was a
  *delete* through a linked problem directory. `.ps` and the `.gitignore` were already #360's. The
  lock and the stale-lock removal are not exposed; the watch token, the session file and git's temp
  files are outside the root.
- **The bound.** `a4fd9ef` adds `RecordWrites` (`adapter/store`, internal): every directory from the
  real root walked one name at a time, created where absent, and each a real directory whose real path
  is the path walked; the walk is bounded at `problems` or the root. Operations: `replace` (temp and
  move, a link replaced; mode kept, or owner-only on request), `writeOnce`, `appendLine` (regular file
  or none, no-follow), `createNew` and `deleteIn`. A refusal is one WARN per reason, never quoting a
  link's target, then thrown unless the writer asked to skip.
- **The boot pass.** `cd37817`: `attachPending` leaves a record whose attachment throws pending
  (`DEFERRED`) and goes on to the next record. It ran unwrapped at boot, so a standing refusal would
  have crash-looped the container.
- **The writers.** `592c02d` (under `problems/`) and `2c064fa` (the root's level):
  - Record writes throw: code files (still owner-only), the run log, the raw copy, the statement and
    the submission log.
  - The page, the index, the tag notes, the examples and the runner skip.
  - A seed that is a link is left alone.
  - The heartbeat replaces a linked marker and reads without following one.
  - A linked `statement.md` is now replaced once rather than refetched at every boot.
  - Pressing Run Code after a refused `examples.json` (the comment's route) replaces the link.
- **Pins from the mutation review.** `6e512d7` a marker linked to a changing file no longer makes the
  repository look held. `c707631` a failure that is no refusal still reaches a skipping writer.
  `cd087a5` a root-level write outside the root is refused for the true reason.
- **Tests.** 73 new (2,044 → 2,117; 9 skipped, as before: 8 C# and the `icase` test). Red evidence
  was saved per class:
  - 20 of the 33 first helper tests failed against a stub making the old raw `Files` calls.
  - Every new writer test was red against the unchanged writers on the exposure itself, except two
    pins (owner-only code files; a dangling link where the raw copy goes, refused already) and two
    `CodeArtifacts` cases that were red only on the warning (the rename never wrote through a link
    at the file).
  - `attachPending` threw out before `cd37817`.
  - The heartbeat read threw `RecordRepositoryLockedException` against the old read.
- **Mutation.** 37 mutants, 35 killed. The two survivors are race-only: the append's
  `NOFOLLOW_LINKS`, and the seeds' raw write behind their link check. The climb-out check survived
  until `cd087a5`.
- **Measured.** Over 2,000 writes on this host: a replace 0.22–0.24 ms against 0.04 ms, an append
  0.07–0.08 ms against 0.03 ms.
- **Docs.** ADR [[decisions/2026-10-08-no-writer-follows-a-link]] (the audit table, options, each
  writer's posture, costs), the index, #354's pointer, and a `SECURITY.md` posture line.
- **Pending.** Live: after a rebuild, pages and code byte-identical, `git status` clean after a boot
  with nothing to recover, and no `Not writing` or `Replacing` line. CI on the three OSes has not run;
  Windows skips every link test. Follow-ups: `JsonlRecordStore.read()` and `RunLog`'s idempotency read
  still follow a link (readers, outside #354's bound); handle-based writes, with #360's.

## 2026-10-08 — #361 review round (security S1–S5, quality Q1–Q7)
- **Two reviews of `dd259a2`**, neither blocking the merge. Four findings were fixed on the branch,
  each test red against the code before it; the coordinator files the rest.
- **Folded aliases (S1, Q1, Q2).** `0523221`: each directory must be listed in its parent under
  exactly the name walked, and both the listing and the real path are compared as text after NFC.
  - The review measured `wrote into alias: true` in the image, where the real path echoes the name.
  - It predicted that Windows' case-blind `Path.equals` fails the folding test.
  - It measured that an HFS+ image refused the first write into a new Korean-titled directory.
  - The fix takes the listing and the real path through a `DiskAnswers` seam, like
    `StateDirectory`'s listing, and `namesOnDisk` is now shared. Tests play the image, HFS+ and a
    real path that leads elsewhere.
  - Cost, median of nine rounds: a replace beside 700 sibling directories went from 234 µs to
    617 µs, and a boot's 110 replaces from 26.3 ms to 36.4 ms. Listing 700 entries takes 0.36–0.53 ms.
- **The root-level bound (S3).** `5a160c3`: no `.` or `..` below the root. The target is no longer
  normalized, and the root is walked as configured. Each root-level writer keeps an allow-list of
  first names: `log`, `tags`, the seeds, the heartbeat marker. Both measured paths
  (`log/../.ps/git-credentials`, `.git/hooks/pre-commit`) were written before the change.
- **A hard link (S2).** `fefb790`: an append refuses a file with `unix:nlink` above 1. It is skipped
  on Windows, which has no `unix` view.
- **The boot pass (Q3).** `d76b2c9`: an I/O failure is logged by its class, any other fault as an
  ERROR with its stack, and cancellation is rethrown. The fetch is already wrapped by `fetched`.
- **Pin.** `69687d3`: the `problems` name itself is refused.
- **Mutation.** 16 mutants of the new behaviours, plus two rerun on rewritten lines; all killed.
- **Gates.** check, test, build, `verifyBranchCoverage` and guards all exit 0. Tests: 2,128, 0
  failures, 9 skipped. Coverage: `adapter/store` 84% (615/726), `application` 88% (340/383).
- **Follow-ups the coordinator files**, also in the ADR's costs:
  - Q4: a rename over an open file on Windows.
  - Q5: a crash's temp file, committed by the next reconcile.
  - Q6: duplication with `StateDirectory` and `AtomicStateFile`.
  - S4: `WatchToken.writeText`.
  - S5 and Q7: the reads that still follow a link.
- **Not run** in the image, on Windows or on HFS+ itself; CI has not run the branch.

## 2026-10-08 — #387 the reads that still followed a link, and the watch token
- **Branch** `fix/387-reads-never-follow-links`, from `41f713e` (main with #354, #360, #361).
- **Audit first.** Every read in `src/main`, by path, by whether it followed a link, and by the change;
  the table is in the ADR. The issue named three readers (the log, `RunLog`'s duplicate check, the
  seed ledger). Two more were found, the raw work list's replay, which makes records, and
  `VaultDashboard`'s own reads of a seed (a FIFO there blocked the boot), and a way of reading wrong
  several shared: `Files.exists`, which read a directory that could not be searched as "nothing there". Every reader under `problems/` already went through
  `ProblemFiles` but `RunLog`'s check.
- **The bound.** `723b544` takes the walk out of `RecordWrites` into `RecordBound`, `RecordWritesTest`
  unchanged; `828fd0d` adds `RecordReads`, which reads through it, and asks existence of the filesystem.
- **The refused read.** A refused log is thrown, never answered as empty:
  - `23275fc`: MCP answers a fault rather than "no submissions";
  - `af64882`: the writer reads its history when the first grading needs it, and records nothing until it
    can, with no restart;
  - `8295461`: the boot's attachment pass says so and goes on.
- **The readers.** `7098d45` `RunLog`'s check through `ProblemFiles`; `874d731` each seed read once
  through the bound, the ledger hashing bytes; `d2bc06c` the work list listed and replayed through no
  link.
- **The watch token.** `6137b75` written by `AtomicStateFile`, beside and moved, owner-only from
  creation; a link there is replaced, said. A new adapter edge, `web → store`, in development-rules §1.
- **Junctions.** `2fe85b7` two tests enabled on Windows only, made with `mklink /J`; not yet run.
- **Mutation.** 29 mutants, 27 killed. `3235b97` pins the replay's FIFO check, whose mutant survived
  until then. Two survive, race-only: the read's open and the replay's open without `NOFOLLOW_LINKS`.
- **Gates**, all exit 0: check; test 2,178 (43 new, 0 failures, 11 skipped: the 9 as before and the two
  junction tests off Windows), node 4 of 4; build; `verifyBranchCoverage` (`adapter/store` 85%,
  `adapter/web` 80%, `application` 88%); guards.
- **Docs.** ADR [[decisions/2026-10-08-a-refused-read-is-not-an-empty-one]], notes in #354's and #361's
  ADRs, the index, `SECURITY.md`, `docs/mcp.md` and its twin.
- **Pending.** CI on the three OSes, and the junction tests' first run on windows-latest. Live: after a
  rebuild, every MCP answer and page should be unchanged, with no `Not reading` line at boot.
- **Review round.** The adversarial review of PR #398 blocked it, measuring on the APFS host and in the
  runtime image (uid 1000):
  - High-1, `orphans()` follows links: a pulled link to `/proc/self/fd/1` or a FIFO under
    `.ps/raw/orphans` hung the boot and timed out every MCP tool. The ADR's audit row and accepted cost
    are corrected; the code fix is #378's.
  - Medium-1, `9f63366`: a failed append withdraws its raw copy and forgets the writer's indexes, read
    again from the log at the next grading. The critic's sequence now gives attempt 2, not 4, and a
    replay finds no leftover in its way.
  - Low-1, `588a52b`: `/watch` answers without the last record and says why in `recordsUnread`, never
    500; the badge shows red `!`.
  - Low-2, `af8796b`: `AtomicStateFile` reads a regular file only, so a FIFO at the watch token no
    longer hangs the start.
  - Mutation: 18 mutants, 17 killed, two of them after the pins in `46acd3d`; one survives,
    near-equivalent.
  - Gates, all exit 0: check; test 2,224 (18 new, 0 failures, 11 skipped as before), node 4 of 4;
    build; `verifyBranchCoverage` (`adapter/store` 85%, `adapter/web` 82%, `application` 89%); guards.
  - Pending, as before: CI on the three OSes, the junction tests' first run, and the live check.
## 2026-10-08 — #377 a raw session git delivered is never replayed (branch fix/377-raw-replay-guard)
- **Both findings reproduced** before any change, as tests that failed against `41f713e`:
  - the reconciler recorded a session under a state directory git tracks anything in, and one behind
    a linked raw directory (`recorded: expected 0 but was 1`, each);
  - `unprocessed()` returned a session real git checked out, and sessions behind a linked `.ps` or
    `.ps/raw`;
  - once git tracked a file under `.ps` mid-grading, the next frame of the grading in flight was still
    written to `.ps/raw`.
- `9c45aa7`: the work list is read only where a frame would be written, through `forWriting()` and then
  `pathFor("raw")`, the judgement every raw write takes. A refused session is left in place, unread,
  and one WARN per reason says how many and why. Through a link nothing is listed or counted. The
  commit adds 11 tests: 9 in the store, one of them against real git, and 2 in the reconciler.
- `1ce2977`: the session verdict's cache is kept, its bound stated in the KDoc and pinned by 1 test.
  Measured on the host (APFS, git 2.48.1) with 500 to 5,000 index entries:
  - `forWriting()`: median 7.2–8.1 ms;
  - `tracksAnything()`: 6.8–8.0 ms;
  - `pathFor("raw")`: 0.007 ms;
  - an append: 0.03 ms.

  The image was not measured, because the sandbox refused to run git in a container.
- **Mutation.** Each of 12 mutants ran against the whole suite, and all were killed. They were the
  guard, `forWriting()` and `pathFor("raw")` each removed, `forGit()` in its place, counting through a
  link, the WARN's silence, once-ness, count and reason, deleting what is refused, and git asked per
  frame or per log.
- **Gates**, all exit 0 on the branch rebased onto `ff56a5f` (#392):
  - check;
  - test: 2,147 JUnit tests in 161 classes, 0 failures, 9 skipped as before; node 4 of 4;
  - build;
  - `verifyBranchCoverage`: `adapter/store` 85% (631 of 742), with all 16 new branches covered;
  - guards: 12 of 12.
- **Docs.** An Outcome note in [[decisions/2026-10-08-reconcile-never-stages-the-state-directory]]. No
  new ADR: the cache is round 4's design, kept with a measured bound.
- **Remaining, at `4008e8f`.** The review round below closes the first two.
  - Once `.ps` was untracked, as the TRACKED reason advised, a session git delivered was replayed at
    the next boot. The WARN named `git ls-files .ps`, which lists no folded spelling.
  - An untracked link at a session file was listed, and the reconciler read through it.
  - `orphans()` is unguarded.
  - #378 items 2–3 are untouched.
- **Pending.** Pushed as PR #395 by the coordinator; CI was green on `4008e8f`. Not verified live.

## 2026-10-08 — #377 review round: PR #395's M1, M2, M4 and L1–L5 (branch fix/377-raw-replay-guard)
- **The review.** An adversarial review of PR #395, at `4008e8f`, found nothing blocking. It measured on
  real git 2.48.1 on APFS that the forged replay was deferred, not blocked: upstream force-added a raw
  session, the owner pulled, ran the TRACKED advice `git rm -r --cached .ps` and restarted, and the boot
  recorded the forged PASS (`recorded=1`). Commits on top of `4008e8f`, no rebase. Each fix has a test that
  failed against the commit before it, on behaviour.
- `fca29fc` **L2.** Git is asked first, and the link check runs just before the listing. A `.ps/raw` swapped
  for a link inside the git question was listed through before.
- `8c24551` **L1.** Only regular files are listed. A session file that is a link was listed and recorded;
  the reconciler now reads without following a link at the file.
- `35fc42f` **M1.** `TrackedState.pathsEverTracked()`, answered by one
  `git log --all -m --root --no-renames --name-only -z`. A session whose `raw/<name>` git's history names,
  in any case, is never replayed; while git cannot say, none is. Ten tests, the end-to-end one with real
  git. Measured on this host, median of 21, synthetic histories:
  - 166 commits (460 KB tree): the question took 10.7 ms; `unprocessed()` with one session waiting 20.1 ms;
  - 1,660 commits (4.5 MB tree): 33.6 and 40.7 ms;
  - with nothing waiting git's history is not read (7.9 and 8.4 ms, the index question alone).
- `bab8bb8` **M2, the TRACKED advice, L3, L5.** The reasons give `git ls-files -- ':(icase).ps'
  ':(icase).pſ'` and `git rm -r --cached --ignore-unmatch -- ':(icase).ps' ':(icase).pſ'`; TRACKED says to
  delete from disk what git put there first. A parameterized test runs both commands as the owner reads
  them, for eight spellings; the old reason had no listing command, and its `git rm -r --cached .ps` left
  `tracksAnything()` true for `.PS` and `.pſ` entries (probed). HOLDS_A_LINK and NOT_THE_DIRECTORY say to
  move what lies behind the link first; the uncounted WARN names no link.
- `0de3f66` **M4.** `RawSessionLog.unreplayed()`; `incompleteHistory` carries `sessionsNotReplayed` and
  `rawDirectoryNotListed`; `docs/mcp.md` and its twin (blob `70f95ca`). `ec783c3` counts what is left in one
  place.
- `8362c46` **Pins.** `--root` under `log.showRoot=false`, the `raw` segment, one WARN.
- **Mutation.** 32 mutants, each against the whole suite, all killed; the table is in the ADR.
- **Gates**, all exit 0: check; test (2,181 JUnit in 161 classes, 0 failures, 9 skipped as before; node 4
  of 4); build; `verifyBranchCoverage` (`adapter/store` 85%, 641 of 754; `adapter/git` 85%, 232 of 270;
  every package at or above its floor); guards (12 of 12).
- **Docs.** The #360 ADR's #377 Outcome: the review round, M1's cost, L4's measured collision and the
  mutation table. No new ADR: these fix the round's own guard; no new decision.
- **Remaining.** `orphans()` unguarded (M3, for #378); a session git delivered and the owner only untracked
  stays on disk, never replayed and counted at every start; a git that cannot answer holds the work list;
  the reflogs are not read; a directory swapped after the listing is read through. Not measured in the
  image; CI has not run this round; not verified live.
- **Pending.** Not pushed; the coordinator merges main in and pushes.
## 2026-10-08 — #373 the push gate reads each new object once (branch perf/373-scan-new-objects)
- **Measured on main first.** Synthetic histories shaped like the #360 review's — each commit adds one base64 file, so nothing is token-shaped and every search runs to the end — plus the tracker's own shape, each commit also appending a line to `log/submissions.jsonl`. Through `push()` to a remote with a URL and nothing behind it (git 2.48.1, M4 Pro): first push 25.2 s (1,660 commits, 4.2 MB tree), 252.5 s (5,000 commits, 9.8 MB), 56.3 s (the log history, 729 MB of blob versions); the slowest `git grep` call, replaying the same calls in a shell, 3.3, 16.1 and 6.9 s.
- **The scan** (`58de882`, wired in `9a79453`). `rev-list --objects <range>` names each object once; `cat-file --batch-check` gives types and sizes (50,000 ids a call); `cat-file --batch` prints the blobs, about 64 MB of content a call, read through `GitProcess.runReading` (`43a111c`, stdout from its temp file as a stream) by `BatchOutput` (`8da5a08`) in 1 MB windows that repeat `TokenPatterns.overlap` bytes (141). The range is unchanged and decided in `outgoingRange` alone; only blobs are read (commit and tag messages stay #375's); the commit side stays `git grep`; the cache key is unchanged. Every failure is `CREDENTIAL_UNSEARCHED`: rev-list or batch-check failing, a description missing an id or saying `missing`, `--batch` failing or timing out, a header not as asked, content cut short, no newline after it, output left over.
- **Matching** (`TokenPatterns`, `983b8b6`): bytes read as ISO-8859-1; the shapes moved there from `CommandLineGitSync`; stored values matched as the UTF-8 bytes `git grep -F -f -` is fed, split as it splits them. Two parity tests run `git grep -E` and `-F` under `LC_ALL=C` on the same probe files and require the same files found.
- **The coordinator's two #372-review findings, folded in.** A token beside `0xE9`, `0xC0 0xAF` or `0x80` — measured here: `en_US.UTF-8` git found none of five probe files, `C` all five — is pinned in the parity probes, in real blobs the scan reads, and in `BatchOutput`'s bytes. And a UTF-16 view: a window that holds a NUL is also read as UTF-16, both byte orders, from its first and second byte; `a token in a UTF-16 file is never pushed` was red on the old push (`expected:<false> but was:<true>`, the token went out).
- **`GitProcess`** (`8493d88`). Input is written from its own thread and a git past its timeout is killed through its handle: with 1 MB of input and a 1 s timeout the call took 10.1 s and reported success (input on the caller's thread), then 10.0 s (`Process.destroyForcibly` closing stdin waits for the write); now 1.1 s.
- **After**, the same harness: first push 0.21–0.33 s, 0.52–0.63 s, 4.3–7.2 s; five outgoing commits 0.12–0.18 / 0.13–0.16 / 0.16–0.37 s (main 0.22–0.31 / 0.48–0.54 / 0.31–0.33); nothing outgoing 0.09–0.14 s (main 0.07–0.13). Slowest single call 0.14 s, 0.38 s (`rev-list --objects`), 1.03 s (one 64 MB `--batch` call, matching included). The git behaviours the refusals rest on were checked on 2.53.0 in the tracker's image: identical.
- **Tests.** New: `TokenPatternsTest` 19, `GitObjectTest` 11, `BatchOutputTest` 19, `OutgoingObjectScanTest` 22; `GitProcessTest` +4, `CommandLineGitSyncTest` +3. Red first against stubs that answered clean or read everything whole (15 of the first 16, then 8 against bytes-only; 10/11; 8/19 plus an `OutOfMemoryError` on the endless header; 17/20), and the UTF-16 push test against the old wiring. Existing push-refusal tests all green: the middle commit (new), another remote (N4), the stored value, both shapes on the push side (fine-grained new), the unreadable blob and tree, the unlistable range, replace refs, the cache.
- **Mutation** (36, `mutate.py` in the scratchpad, each reverted): 34 fail a test; the unbounded header exhausts the test JVM's heap (`Java heap space`) on its test; 1 equivalent (content ending early taken for the object's end — a short read is only ever the end of the output, and the newline check after it fails too). Planning the run found three checks no test could fail (the CR, the batch-check exit code and its every-id check), pinned in `53c6598`; the run left UTF-16LE alone and the NUL gate surviving, pinned in `37bb284` and run again. Per mutant in the ADR.
- **Docs.** ADR [[decisions/2026-10-08-the-push-gate-reads-each-object-once]]; the #360 ADR's decision 4 bullet and two costs marked superseded (not its Outcome, which #389 appends to); index.
- **Gates**, all exit 0: check; test (2,129 JUnit across 164 classes, 0 failures, 9 skipped — 8 C# and the `icase` test on this case-insensitive host; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 88%, 306/345, from 85%, 225/262; `adapter/config` 65% at its floor); guards.
- **Found, not fixed.** The commit side's `git grep` still lets a token in UTF-16 text into a local commit; the push then refuses every attempt until history is rewritten. Out of #373's scope.
- Pending: a rebase onto #389 (`GitProcess` PINNED and `CommandLineGitSync`'s `isDirty` area are its; this branch changes `feed`, the kill and `runReading`, and the gate's plumbing); CI on three OSes (Windows skips the four new tests that run shell aliases); not verified live.

## 2026-10-08 — #373 review: every push reads HEAD's own tree (branch perf/373-scan-new-objects)
- **The regression** (the review of PR #396 at 315f44e, Medium). The tracker never fetches, so a remote-tracking ref stays where the last push left it. After `remote set-url` to a new remote, or with the remote re-created empty, `origin/main` still named a commit whose tree held a token, the range left that blob out, and a clean commit on top went out with it. On main, `git grep` read HEAD's whole tree and refused.
- **The fix** (`eaab208`). The push lists `HEAD^{tree}` beside its range; the scan reads what either listing names, each object once. A commit behind a stale ref was not read before #373 either, and stays #376's.
- **Red on 315f44e**: both reviewer scenarios as real-git tests, `expected:<false> but was:<true>` (the push went out). Scan tests red against a first-listing-only stub (2) and a no-dedupe stub (`expected:<1> but was:<2>`).
- **Mutation** (4, all killed): HEAD's tree not listed 2 tests; no dedupe 1; a failed listing taken for an empty one 5; the first listing alone 4.
- **Cost**, same harness and histories: first push unchanged (0.21–0.28 / 0.50–0.59 / 3.9–9.5 s; HEAD's blobs are in the range already); five outgoing commits 0.17–0.25 / 0.24–0.29 / 0.19–0.21 s (was 0.12–0.18 / 0.13–0.16 / 0.16–0.37; main 0.22–0.54); nothing outgoing 0.20–0.46 s (was 0.09–0.14). HEAD's tree: 4.2 / 9.8 / 3.4 MB of blobs, listed in 0.04–0.29 s.
- **Docs.** The ADR's Decision 1, its Rationale table (a column for HEAD's tree), two costs rewritten — what a stale ref still leaves out, stated precisely — and an Outcome paragraph; the index line.
- **Gates**, all exit 0: check; test (2,246 JUnit across 166 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 88%, 311/353); guards. Two earlier coverage runs failed in `TrackerApplicationTests` alone: its fixed records path under the system temp directory was locked by another worktree's concurrent test run. Not this change; found, not fixed.
## 2026-10-08 — #394 the context test gets a directory per run (branch test/394-context-test-own-dir)
- **The defect.** `TrackerApplicationTests` booted into `${java.io.tmpdir}/programmers-tracker-context-test/records`, one path for every checkout on the machine. The record repository's lock was doing its job against the test: overlapping runs refused each other, and each began from what the last left. Here that was a repository with two `chore: reconcile uncommitted records` commits from 2026-10-07 and the state of every run since.
- **Red.** Two `git worktree` copies of `afb55e5`, `./gradlew test --tests '*TrackerApplicationTests'` started together. One passed; the other failed at startup with `RecordRepositoryLockedException: Another programmers-tracker instance is already recording into …/programmers-tracker-context-test/records`. Its whole 4.9 s run fell inside the other's 8.6 s. A first attempt that started 5 s apart did not overlap and passed both, so the collision needs the timing.
- **The fix** (`0db43e9`). A static `@TempDir`, handed to Spring by `@DynamicPropertySource`, holds `tracker.record-repo`, `tracker.session-file` and `tracker.watch.token-file`. The last two defaulted to `.ps/` in the checkout, where a run generated `.ps/watch-token`. A second test compares the three resolved settings with the directory and checks that `records` was created in it.
- **Cleanup order.** `@DirtiesContext(AFTER_CLASS)` closes the context before JUnit deletes the directory. A probe run just before the deletion found, with `@TempDir` alone, the lock held by the still-open context and the heartbeat marker present; with it, the lock free and the marker gone. macOS only, where the deletion goes ahead either way. Windows was not run, so the reason this is there is first checked by CI's windows-latest leg.
- **Green.** With the fix, five rounds of two concurrent runs from the same two copies: 10 of 10 passed, the windows overlapping by 0.2–7.5 s, no refusal. Mutants of the paths test: the watch-token-file registration removed, killed; the session-file key mistyped, killed; `session_file` survived because `Environment` reads it as `session-file`, the same key. The record-repo redirect was not mutated: without it the boot reaches `~/ps-records`.
- **Other fixed paths in `src/test`.** None else is shared between checkouts. `build/tmp/mcp-controller-test` and the three `build/tmp/*-should-not-be-created` names sit under each checkout's own `build/`; `/tmp/ps-records` and `/var/tmp/records` are strings that are compared and never opened; `SessionControllerTest` and `PlantedLinkFixtures` take random temp names. No fixed port: `--server.port=0` is the only one.
- **Corrected in passing.** The class comment said the scratch directory is not a repository. Since #258 the boot initialises it and reconciliation commits there.
- **The GitHub token** (`bc09917`). With one exported, the boot creates or finds a private repository named after the record directory and pushes to it (`GithubRemote`). `0db43e9` made that every run: a fresh repository has no `origin`, where the shared one had it wired after the first. `tracker.github.token` is now registered empty in the same `@DynamicPropertySource`, and a third test asserts which source answers the key, not its value, which is empty with or without the pin wherever no token is exported. Printed from a running context, the sources are `Dynamic Test Properties` (#0, `DynamicValuesPropertySource`), Boot's `configurationProperties` view (#1, left out of the check: it answers whatever the rest do) and the yml (#8).
- **Token mutants.** The registration removed, and its key mistyped (`tracker.github.tokn`): both fail with `expected:<Dynamic Test Properties> but was:<Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'>`. Neither could send a request. The environment was checked by name first (no `GITHUB_TOKEN`, no `TRACKER_*`), and no run ever set a token, since a red run with one would send the request the pin prevents.
- **Other variables.** `application.yml` reads 17. Pinned: the four above. The rest cannot reach out from this boot. `TRACKER_CABLE_URL` and `_ORIGIN` are dialled only when `/watch` asks for a subscription. `TRACKER_PAGE_BASE` is fetched only for a record in the log still missing its code or statement, and only with a session file; the log is new, the file is absent, and the boot logged no fetch. `TRACKER_BACKUP_*` decide when a push is due, and a push needs an `origin`. `TRACKER_PORT` and `_BIND_ADDRESS` bind nothing in a mock web environment. `TRACKER_WATCH_TOKEN`, `_RECORD_REPO_LOCK`, `_HEARTBEAT_*` and `_MCP_ALLOWED_ORIGINS` open nothing. The application imports no `.env` (compose reads it), has no catalog fetch (the catalog is a classpath snapshot), and `GitProcess` strips `GIT_DIR`, `GIT_WORK_TREE` and `GIT_INDEX_FILE` from git's environment.
- Gates, all exit 0: check; test (2,165 JUnit across 162 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (`adapter/config` 65%, at its floor, as before); guards. No ADR; the last commit carries `Wiki-Skip`.
- Pending: CI has not run this branch.

## 2026-10-08 — #390 a backup that did not count backs off; no remote says nothing (branch fix/390-backup-says-it-once)
- **The defects.** With no remote — a documented setup, said once at INFO by the boot report and `BackupSchedule` — the day never counts, so every one-minute check logged `CommandLineGitSync`'s `NO_REMOTE` and `DailyBackup`'s "could not push": 2,880 lines a day. And since #372 a held day stays due, so every check ran git, the records repository's hooks and the content search again, each refusal said: 1,440 a day (the review of #389 measured 123 ms a held check and 11 lines in 10 checks).
- **Measured before** (`e6deb23`, the head of #389 that main took as `cb78438`), one simulated day of checks a minute apart, the real `DailyBackup`, `CommandLineGitSync` and `FileBackupLog` on real repositories, git 2.48.1: remote-less, 1,440 reconcile-and-push rounds, 2,880 WARN lines (1,440 + 1,440), 161 s of git work; held (200 files, one note the gate refuses, a local bare remote), 1,440 rounds, 1,441 lines (1,440 refusals, 1 held line), 204 s. Neither recorded a backup.
- **`6b8e03e` a remote-less push says nothing.** `noRemote()` asks `hasRemote()`: with no remote at all the push answers false quietly; a remote that exists while the branch pushes to another name with no URL is still said. Red first: the no-remote push test heard the warning. Pin: a `backup` remote beside a missing `origin` is said once, nothing searched.
- **`d55c40f` no remote is not a failed push.** Port decision: no change. `push()` keeps its Boolean; `DailyBackup` asks the existing `hasRemote()` only after `push()` answered false — the question `BackupReporter` already asks to tell the supported setup from the fault — so a branch with no commit yet still answers true and counts (#372). Red first: ten checks gave 20 lines (10 + 10). Pin: a remote whose URL leads nowhere still says "could not push" and the day stays due.
- **`82f2f97` retries back off.** Backoff decision: a try that does not count (held, not pushed, no remote) is tried again a minute after the first failure, then twice as long each time, at most an hour apart (`BackupRetry`, pure); the next scheduled backup starts over, and so does a restart. Seven tries in the first two hours, hourly after; a fix is picked up within the hour. The held line keeps #372's once per scheduled backup; "could not push" is said at each try; both say when the day is tried again. A dedupe per reason and head was weighed: quiet logs, but git and the hooks would still run 1,440 times a day, and the gate's warning sites move with #373. Red first: a held day across 120 checks tried 120 times (7 after); a failing push across 16 checks said it 16 times (5 after) and lands at minute 31 once its remote exists; a fix picked up at minute 70 (123 after, not before); the next evening's 23:00 tried at once although the last backup waited until 23:08 (56 tries before, 7 after). `BackupRetryTest` red as a compile error. The #372 test of the held line once per scheduled backup now ticks across tries, since three calls at one instant no longer try.
- **Measured after** (`82f2f97`), the same day: remote-less, 29 rounds, 0 WARN lines, 2.7 s; held, 29 rounds, 30 lines (29 refusals, 1 held line), 3.9 s. Neither recorded a backup.
- **The real server**, jars built from `e6deb23` and `82f2f97`, each booted against its own copy of a scratch remote-less repository, the backup due at once and checked every second (`TRACKER_BACKUP_CHECK_INTERVAL=PT1S`), every outside address on a closed local port, a throwaway watch token, no GitHub token: before, 134 "git push skipped … no remote" and 134 "could not push" in about 150 s; after, none in 200 s, and records dropped in at +34 s and +74 s were committed by the tries at 17:57:35 (boot), 17:58:36 and 18:00:37 — a minute, then two. Neither recorded a backup. Both logged five boot lines about the scratch repository's synthetic submission line (unrelated).
- **Mutation** (all killed, each reverted): the no-remote warning back in `pushed()` 2 tests; the misconfigured remote never said 1; the backup warning on every false push 1, or on none 1; no backoff 4; the backoff blind to the scheduled backup 2; failures not counted 6; no hour cap 3; no cap on the shift 1 (the 59th failure overflows `Duration` and threw); the held line at every try 3. Equivalent: a try that counted keeping its retry.
- **Gates**, all exit 0 on the final tree: check; test (2,192 JUnit across 163 classes once the review of #399 was applied, where before it the review counted 2,177 and this said 2,175; 0 failures, 9 skipped — 8 C# and the `icase` test on this case-insensitive host; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 86%, 239/276; `application` 89%, 370/413; `adapter/config` 65%, 25/38, at its floor); guards. One earlier test run failed `TrackerApplicationTests` with "Another programmers-tracker instance is already recording into …/programmers-tracker-context-test/records": that test boots against a fixed path under `java.io.tmpdir`, and another worktree's suite was running at the same time. It passed alone and in the final run. Found, not fixed: two suites on one machine collide on that path.
- **Docs.** New ADR [[decisions/2026-10-08-a-backup-that-did-not-count-backs-off]] (the port and backoff decisions, the options, the numbers, the costs), indexed; the wire-git ADR's decision 4 points at it and its no-remote cost is marked as it really was; the #360 ADR's "Refusals repeat" cost and its "left to #390" note point at it.
- **The review of #399 applied** (approved, 0 blocking; six findings, all fixed on this branch, each code change red first): `934627d` `BackupRetry.of`, its doublings counted from `FIRST` and `LONGEST`; `baffe2b` a clock set back tries at once (red: set back 10, 30 and 60 minutes, it waited as much); `34e80ef` the backoff decided by whether the day is still due, in a `finally` (red: a throwing log write, 60 pushes and 60 exceptions in 60 checks; a skipped write, 60 pushes; 6 each after), which disproves the "equivalent" mutant above, now killed by a pin of its own; `7fa8404` `hasRemote()` never throws (red: a records directory gone threw out of `runIfDue()`); `46fe100` the retry lines true at every try; `f7dba56` the boot wiring's failure says a restart retries instead of promising the backup will speak; `26e4b4d` a remote evidently wanted — a backup recorded before, or a push credential stored, the new port question `GitSync.hasPushCredential()` answered by the store's presence — and missing is said once for each scheduled backup (red: said zero times). Docs: `docs/bootstrap.md` and its Korean twin (the stale `No configured push destination` row replaced; a row for the new warning, with how to keep records local), the ADR (Context, the 4-hour cap's 13 tries, decisions 1–4, three accepted costs added and one extended, the review's outcome), the index entry and the wire-git ADR's summaries. Mutation: 20 mutants, all killed.
- Pending: CI has not run this branch. Accepted: a standing refusal still says its reason at each try, about 29 lines a day; a fix waits up to an hour; the backoff is in memory; the boot report still calls a missing remote a supported way to run, the backup's daily warning being what says otherwise; a remote removed on purpose is warned about daily until its evidence is deleted.
## 2026-10-08 — #375 the push reads commits and names, and sends no tag (branch fix/375-scan-messages)
- **The gaps.** N6 in #360's review: a token in a commit message, an author or a committer went out, since the push gate read files alone. A token in a file or directory name was invisible to every search, `git grep` included. And with `push.followTags=true` in the records repository, an annotated tag on the pushed branch went out beside it, its message never read (measured: `refs/tags/v1` on the remote, a token in its message).
- **Commits** (`1aa32b3`). The scan's `cat-file --batch` prints the commits it already lists, through the same `TokenPatterns` (UTF-16 view included). A commit's header, up to the empty line that ends it, is searched apart from its message — a window's seam can split that empty line, and a test puts it there. The refusal names the commit by a 12-character id and the part ("the message", "the author, committer or another header line"), never the token, and still says to revoke it.
- **Trees, as bytes alone** (`a615a18`). The names live in trees; a hit is "a file or directory name", never which. Object ids cannot make a false match: a token-class run touching an id is cut by the NUL before it and the space after the next mode, 26 bytes at most against 40; measured on the 5,000-commit history, the longest such run in 12,512,500 entries was 10 bytes and no window of its 15,000 trees (382 MB) matched. No UTF-16 view for trees: a name cannot hold a NUL, and the view cost 6.2–7.1 s on those trees against 1.0–2.0 s for the bytes alone.
- **Tags** (`699102f`). `--no-follow-tags` on the push: no tag goes, so none needs reading. A `mergetag` header is part of its commit and read with it.
- **Before that**: `7d5e7e0` made `SearchOutcome` a sealed type that can say where; `c88ddda` merged the #373 fix (eb7637a) and main (804215b), `c42a783` merged main with #373 squashed (99cd754, a tree identical to eb7637a, so no file changed). The WIP commit was dropped before the first merge was redone, never pushed.
- **Red first**: commits — 12 tests against types with no behaviour (pushes went out, `expected:<false> but was:<true>`; a commit hit read as a blob's); trees — the scan answered `Clean` for both names, the push went out, the bytes-alone matcher found the UTF-16 token; tags — the remote held `refs/tags/v1`.
- **Cost** (whole `push()`): first push 0.44–0.54 / 1.82–2.40 / 4.7–6.9 s (before 0.21–0.28 / 0.50–0.59 / 3.9–9.5; main 25.2 / 252.5 / 56.3); five outgoing 0.14–0.22 / 0.22–0.26 / 0.17–0.19 s (unchanged within noise); nothing outgoing 0.15–0.27 s; slowest call 0.33 s.
- **Mutation**: 17 mutants of #375's behaviours, each reverted, all killed: commits not read 7 tests; trees not read 4; a commit searched as a blob 11; the header never ended 4; read as message from the first byte 5; the empty line across a seam unseen 2; the header's tail lost 2; the message's tail lost 1; the boundary window's header side unsearched 5; trees with the UTF-16 view 1; a tree searched as a blob 5; bytes alone read as UTF-16 2; tags followed again 1; the whole id named 1; the part unsaid 1; a commit finding said as content 2; a name finding said as content 1. The two tail mutants failed to compile as first written and were rerun once they did.
- **Docs.** The #373 ADR's Outcome gains the #375 note (decisions, numbers, costs) and its Decision 2 and last cost point there; the #360 ADR's N6 and its "file content, nothing else" cost; `SECURITY.md`; the index line.
- **Gates**, all exit 0: check; test (2,268 JUnit across 166 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 88%, 341/384; `adapter/config` 65% at its floor); guards.
- **Found, not fixed.** The commit side still reads file content alone, so a file named with a token is committed by a reconciliation and every push is then refused until history is rewritten.
- Pending: CI; not verified live.
## 2026-10-08 — #378 held frames and orphans, with the re-check of #377 folded in (branch fix/378-held-frames-and-orphans)
- **Base.** Written on #377's `f807f77` with PR #395's head `3a0cdfb` merged in, then replayed onto main `258ed10` (where #377 landed as `2edfb2a`) before the first push. Commits on
  top, no rebase. #378's git items 1 and 4 are #376's, so every commit says `Refs #378`, never `Closes`.
- `d4de07c` **M3 (High).** `orphans()` reads only where a write may go: `forWriting()`, then
  `pathFor("raw", "orphans")`, then git's history. Only a regular file of 16 MiB at most, judged and opened
  without following a link; never a FIFO, a device or a link. What is passed over is counted
  (`orphanFilesNotRead`), a directory that could not be listed is flagged (`orphansNotListed`), each said once
  with why; `docs/mcp.md` and its twin. 15 tests; 10 red against `f807f77` with the new port types stubbed
  (the FIFO, the link to one and the MCP call timed out; the rest read forged frames), 5 pin the WARNs and
  the one question to git.
- `b6877b8` **Item 3.** An orphan whose file is a link is held in memory, never written through, and written
  once a regular file or nothing stands there; the link is never replaced. Found: the throw also stopped the
  next grading's first frame, through `releaseHeld()`. 3 tests, red.
- `011255c` **The re-check of #377, folded.** F1: `--reflog` in the history question (reset + force-push and
  force-push + `pull --rebase` pinned with real git; cost 9.5 → 10.1 ms on 166 commits, 30.5 → 35.8 ms on
  1,660, median of 21); TRACKED and `SECURITY.md` make the delete from disk mandatory, since expiry,
  filter-repo and a shallow fetch stay open. F2: `Known(paths)` or `Unanswered(reason)` — git's exit code and
  first line, the timeout, or the exception's kind — in both WARNs. F3: what git delivered is neither
  replayed nor counted, said once a start. F4: `docs/mcp.md`'s cases and the twin (blob `6a5a5ce`). F5
  accepted in the ADR. 5 new tests and 3 rewritten, each red first.
- `aa809c8` **Item 2.** Runs set aside and orphans hold three quarters of the 8,000,000-character budget at
  most, so a submit in flight keeps room; red first with `NoSuchFileException` from `complete()`. Measured:
  the full budget retains 16.1 MB (2.01 bytes a character); a run's frames 0.9–1.6 K characters, a submit's
  2.1–2.9 K. Spilling to disk rejected; frames held at exit are lost, and said.
- `f582287` **Mutation pins.** 51 mutants (`mutate378.py` in the scratchpad), each against the store,
  git-history, MCP, application and config tests (1,175): 39 killed. The other 12, and 2 written for F2 after
  the run: 10 pinned with 10 new tests and one assertion, each now killed (1,184 tests); 4 left, which pass
  the whole suite as well (2,329): the orphans reader's two link checks, each alone; a timeout's wording; an
  empty first line. Per mutant in the ADR.
- `fc57595` **KDocs.** `TrackedStateEntries` and the `TrackedState` port say the history question reads
  reflogs, and how far "ever" goes.
- **Gates**, all exit 0, at `fc57595`: check; test (2,329 JUnit in 166 classes, 0 failures, 9 skipped as before;
  node 4 of 4); build; `verifyBranchCoverage` (`adapter/store` 85%, 687 of 806; `adapter/git` 87%, 320 of 365;
  `adapter/mcp` 92%, 340 of 368; every package at or above its floor); guards (12 of 12, docs staged).
- **Docs.** The #360 ADR's Outcome: M3, items 2 and 3 with their options and measurements, F1–F5, the mutation
  table. No new ADR: the decisions are the round's own and are recorded there.
- **Remaining.** Frames held while `.ps` is refused are lost at exit; a forged session the owner only
  untracked replays once git's history stops naming it (expiry, filter-repo, a shallow fetch); F5's race; the
  orphans reader's two link checks each stand in for the other. Not measured in the image; CI has not run
  this branch; not verified live.
- **Pending.** Not pushed.

## 2026-10-08 — #376 + #378 items 1 and 4: each gate searches what its destination lacks (branch fix/376-gate-range-and-scope)
- **The push range** (`4885b82` `RemoteTips`, `3a1b4b8`). `HEAD --not <tips>`, the tips what `ls-remote` lists at each push URL (`remote get-url --push --all`; `ls-remote <name>` asks the fetch URL, measured with a `pushurl` apart), kept where this repository holds them, only what every URL holds. A destination that cannot answer: no push, one WARN until it answers again, never git's words. Cost: `ls-remote` 17.9 ms (local bare path) / 22.7 ms (`file://`) in a shell; `push()` with nothing outgoing 49–63 ms before, 95–133 ms after (+40–70 ms); a real remote adds a round trip, not measured.
- **#378 item 1** (`3a1b4b8`). The clean-search cache is keyed on the head, the destinations, the held tips and the store digest. The gate critic's C1 (`set-url`, `fetch --prune`, the same server sending a history-only token to B) is `a head searched clean for one remote is searched again before it goes to another`, red against main's `CommandLineGitSync` (31152de).
- **#378 item 4** (`b51d317`). A value that is not a remote's name and holds `/`, `\` or `:` is its own destination; `hasRemote()` answers true for it, as #390 asks.
- **HEAD's tree no longer listed at a push** (`e5e0db0`). Redundant with the range — #373's two stale-ref tests pass without it — and it refused every push for a string the remote held: `a push goes ahead when the remote already holds what HEAD carries`, red with it.
- **The commit side** (`e9fefcc` `GitProcess.run` variables, `129da01` `StagingPreview` + `Introduced`, `e16ad46` the wiring). Before anything is staged: the index copied with its time into a temp directory, `add --all -- <scope>` on the copy (`GIT_INDEX_FILE`), `diff-index --cached -z --no-renames <HEAD tree | empty tree>`; the added paths matched as bytes ("a file or directory name", #375's gap), the blobs through #373's scan less what HEAD's tree holds (`rev-list --objects <ids> --not <base>`, 500 a call). Git's own words when the preview fails. No search after staging, no `git grep` left. Copy vs read-tree: 35 vs 565 ms (5,000 files). Reconcile with one new file in 5,000: 872 → 316–373 ms; the first of a process 690 ms → 1.45–2.08 s (HEAD searched whole once).
- **What HEAD already holds**: each reconciliation searches what entered HEAD's tree since the last tree it searched (`diff-tree -r -z --no-renames`), the whole tree first and after the store changes; found → one WARN, never a refusal; a search that could not run is redone.
- **Red first**: all 21 new `CommandLineGitSyncTest` cases against main's `CommandLineGitSync` (31152de); `IntroducedTest` 9/13 and `StagingPreviewTest` 9/10 against stubs; the `GitProcess` test against `run()` ignoring its variables.
- **Mutation**: commit side 32 — 30 killed (a failed preview let through was killed after its test, a required filter that fails only on the copy), 2 equivalent (names read as UTF-16 too; the index assumed at `.git/index`); push side 17 — 16 killed, the cache without the destinations equivalent. The five beside the range and the cache ran again on the final code, after the merges: same verdicts. Per mutant in the ADR.
- **Docs.** ADR [[decisions/2026-10-08-each-gate-searches-what-its-destination-lacks]]; notes in the #360 and #373 pages, their range, commit-side and HEAD-tree lines marked superseded; `SECURITY.md`; the index.
- **Gates**, all exit 0: check; test (2,369 JUnit across 169 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 87%, 429/488; `adapter/config` 65% at its floor); guards (12/12).
- **After #390** (merge `c49e033`, main 258ed10). `hasRemote()` keeps #390's guard and counts a URL remote: one `runCatching` around both; `noRemote()` stays silent with no remote at all; `hasPushCredential()` kept. `088b0a2` pins it with the real adapter and backup: a URL remote is backed up there, and one that leads nowhere has the backup say it could not push. Red against main's adapter (`expected:<true> but was:<false>`; two warnings expected, none said) and, the second, against the merge taking main's `hasRemote()`. Merge mutants killed (main's `hasRemote()` 2, unguarded 2, `noRemote()` loud 3, credential dropped 2); the range and cache mutants a third time, same verdicts. Gates on `088b0a2`, all exit 0: check; test (2,398 JUnit across 170 classes, 0 failures, 9 skipped; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 88%, 436/492); guards (12/12).
- **Found, not fixed.** #375's push reads every name in a tree it sends: a commit that rewrites a directory holding a token-named file the remote already has is refused (probe: a new file beside it refused, one elsewhere pushed); the coordinator files it as a follow-up. Not verified on Windows (the copy's `GIT_INDEX_FILE` path; the shell-filter tests skip there); CI has not run; not verified live.
- Pending: not pushed; the coordinator pushes.
## 2026-10-08 — #387 merged with main at 258ed10, #377 among it (branch fix/387-reads-never-follow-links)
- **`5a86aaa`**, a merge, no rebase. Five files conflicted, all on the raw work list both branches had
  bounded. The listing is #377's (`forWriting()`, then `pathFor("raw")`, regular files alone, no session
  git has ever tracked, `sessionsNotReplayed`), which does all that #387's `listable()` did, so that went.
  The replay's read is main's, with #387's regular-file check kept before its open. `withdraw` is kept.
  `docs/mcp.ko.md` holds both paragraphs, its header the merged page's blob `edb4123`.
- **Where the two disagreed:** a linked or FIFO session is now passed over at the listing (an empty
  report) where #387 failed it at the read (`failed=1`). Three #387 tests were changed to that: the
  linked-raw WARN in #377's words, the linked session's empty report, and the FIFO test split into the
  listing case and a swap-after-listing case that pins the read's check.
- Every test name of both sides is in the merge, but main's two renames and the one #387 removed.
- **Mutation spot checks**: 6 of 7 killed; the read's open following a link survives, race-only.
- **Gates**, all exit 0: check; test 2,403 (169 classes, 0 failures, 11 skipped), node 4 of 4; build;
  `verifyBranchCoverage` (`adapter/store` 85%, `adapter/git` 89%, `adapter/web` 82%, `application` 89%);
  guards 12 of 12.
- The ADR's accepted cost is now precise: `sessionsNotReplayed` counts what the last start left, not
  gradings refused while the server runs.
- **Pending.** Not pushed. CI, the junction tests' first run and the live check, as before.
## 2026-10-08 — #378 PR #401: CI, the release race, the check after git (branch fix/378-held-frames-and-orphans)
- **Base.** On `dd9648b`, the coordinator's rebase of the round onto main `258ed10`. Commits on top, no rebase;
  main `40bc5f5` (#387, PR #398) merged in as `b0dea9e`, three conflicts each keeping both sides.
- `548520a` **CI, all three OSes.** The cut of git's line in a reason was tested on git's own wording: 2.48.1 here
  and 2.53.0 in Docker name a missing git directory in full, CI's gits printed `(null)` (`(NULL)` on Windows). The
  reason is built by `TrackedStateEntries.reasonOf` and pinned there on made results: the cut, the first line that
  says something, a git that said nothing, a timeout. The real-git test asserts the stable start only.
- `24ae432`, `a1578d2` **CI, Windows.** The two new FIFO tests trusted `mkfifo`'s exit code; Git for Windows'
  exits 0 and leaves a regular file. `madeFifo()` requires "other", in #387's text, and both tests assume
  `canPlantLinksIn()` as the other FIFO tests do.
- `8bb62c1` **Medium (the critic, measured: 164 throws in 400 runs, 376 of 400 held frames lost).** A release
  takes, writes, and puts back on a failure; it never throws, so a live grading never fails over a held frame. An
  orphan's own failed write is held; the failure is said once. Three tests on a directory closed to writes, red
  with `AccessDeniedException` before.
- `6960164` **Low (inferred).** `orphans/` is checked again and listed anew once git has answered its history; the
  new test, red before, counted a swapped link's 40 lines.
- **Accepted.** A file swapped for a FIFO between the check and the open hangs `orphans()`; it takes a local
  process with write access to `.ps`, since git cannot make a FIFO.
- **Mutation** (16, `mutate401.py` in the scratchpad): 14 killed; the put-back order for orphans and runs survives,
  as it takes a frame held while a write is in flight. The timeout's wording and the empty first line, left by
  the round's first check, are now killed at the seam.
- **Linux.** The changed classes on Ubuntu 26.04, git 2.53.0, non-root, `eclipse-temurin:25-jdk`: 353 tests, 0
  failed, 4 skipped (a Windows junction, three that need a case-folding filesystem).
- **Gates**, all exit 0, at `6960164`: check; test (2,445 JUnit in 169 classes, 0 failures, 11 skipped: the 9
  before and #387's two Windows junction tests; node 4 of 4); build; `verifyBranchCoverage` (`adapter/store` 86%,
  716 of 832; `adapter/git` 90%, 360 of 400; every package at or above its floor); guards (12 of 12, docs staged).
- **Docs.** The #360 ADR's Outcome gains this round, with the FIFO swap accepted.
- **Remaining.** The FIFO swap; the put-back order under concurrency; CI has not run these commits; not verified
  live.
- **Pending.** Not pushed; the coordinator pushes.

## 2026-10-08 — #386 one write for records and state, and no crash debris in a commit
- **Branch** `refactor/386-one-write-primitive`, rebased onto main at `9b4dcc1` (#387 squashed as
  `40bc5f5`, #377 before it, #376 and #378's store half after it). Two earlier merges, of #387's review
  fixes and of main, are replaced by the rebase; the pin one of them changed is changed where it is made.
- **Inventory first.** Every behaviour of `AtomicStateFile`, `StateDirectory`'s creation and
  `RecordWrites.replaceAt` that an observer could see, and whether a test said it. `a92652d` pins what
  none did: links, hard links and directories at the target, UTF-8, `mkdir`'s modes, and the exact
  words each class says once. The table is in the ADR.
- **One write.** `ce60e12` one `SaidOnce` for four classes; `5fa6c7a` `.ps` verified by the walk's own
  step, `RecordBound.made()`; `a38fc20` `FileReplacement` and its three `FileMode`s. Every pin and every
  test of both callers passed unchanged; only the temporary file's name changed.
- **The debris.** `331c051` names it `.<name>.<n>.programmers-tracker.tmp`, and `RECONCILE_SCOPE` leaves
  it out of every reconcile — chosen over a boot sweep, which cannot precede a live pass or the backup,
  nor reach a file in flight. The coupling is one constant, on the `git → store` edge of §1.
- **Windows.** `8934dc5`, a test enabled there alone: a page held open by `FileInputStream`, which does
  not share deletion, then replaced. It asserts an `IOException` that is not a refusal, the old bytes
  kept and nothing beside. Not run. If it holds, the page is skipped for that write and rewritten at the
  next refresh; no fallback to an in-place write.
- **Mutation.** 21 mutants, 19 killed, one of them after the pin `b1415ed`. Two survive: owner-only left
  to the JDK's default (the same mode on the default filesystem) and `REPLACE_EXISTING` alone
  (race-only).
- **Gates**, all exit 0 on the rebased branch: check; test 2,545 (174 classes, 0 failures, 12 skipped:
  the 11 as before and the Windows pin off Windows), node 4 of 4; build; `verifyBranchCoverage`
  (`adapter/store` 87%, `adapter/git` 88%, `adapter/web` 82%, `application` 89%); guards 12 of 12.
- **Docs.** ADR [[decisions/2026-10-08-one-replace-and-no-crash-debris]]; a note in #361's ADR, with Q4,
  Q5 and Q6 marked; the index.
- **Pending.** CI on the three OSes and the Windows pin's first run. Live: a rebuilt server should write
  byte-identical files with the same modes, and `git status` in the records repository should show no
  `.programmers-tracker.tmp` after a normal boot.
