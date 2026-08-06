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
