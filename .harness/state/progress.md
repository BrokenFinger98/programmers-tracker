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

Caveat kept in both docs: one trial cannot exclude a debounced autosave firing in the same
window. It matters practically, because under a debounce an edit-then-immediately-run
sequence could still fetch stale code.
