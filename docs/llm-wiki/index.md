# programmers-tracker Wiki — Index

Full page catalog. **Read this first** when searching.
Every new page must be registered here (no orphans). Append entries start with the date.

## Decisions
- 2026-08-04 [[decisions/2026-08-04-passive-broadcast-observation]] — Judging integration = passive broadcast observation
- 2026-08-04 [[decisions/2026-08-04-solve-in-web-editor]] — Code is written in the Programmers web editor
- 2026-08-04 [[decisions/2026-08-04-solved-ac-tag-vocabulary]] — Tag vocabulary is the solved.ac 180-tag set
- 2026-08-04 [[decisions/2026-08-04-reject-vector-db]] — Vector DB · graph DB rejected
- 2026-08-04 [[decisions/2026-08-04-no-ai-debugger]] — AI debugger control not adopted
- 2026-08-04 [[decisions/2026-08-04-two-public-repos]] — Two repos · both public
- 2026-08-04 [[decisions/2026-08-04-decisions-live-in-wiki]] — Decision records: wiki ADRs are the single authority
- 2026-08-04 [[decisions/2026-08-04-wiki-push-gate]] — Push gate forces distillation (native pre-push)
- 2026-08-04 [[decisions/2026-08-04-global-project-wiki-split]] — Global/project wikis split into 3 layers
- 2026-08-04 [[decisions/2026-08-04-english-only-artifacts]] — All work artifacts in English
- 2026-08-04 [[decisions/2026-08-04-issue-first-squash-flow]] — Issue-first flow, squash-only merges
- 2026-08-05 [[decisions/2026-08-05-backend-stack]] — JVM 25 · Spring Boot 4.x · MVC+VT inbound · coroutines+Ktor outbound
- 2026-08-05 [[decisions/2026-08-05-hexagonal-architecture]] — Hexagonal (orthodox-hybrid ports) + Functional Core, DDD tactical only
- 2026-08-05 [[decisions/2026-08-05-capture-pipeline-stages]] — Capture is 3 stages; the raw log is the durable queue
- 2026-08-05 [[decisions/2026-08-05-write-serialization]] — Confined single writer, JSONL is the attempt authority
- 2026-08-05 [[decisions/2026-08-05-failure-taxonomy]] — Termination matrix, INCOMPLETE/UNKNOWN outcomes, ping liveness
- 2026-08-05 [[decisions/2026-08-05-ci-guard-scoping]] — CI guards deliberately narrow; coverage report-only
- 2026-08-05 [[decisions/2026-08-05-protocol-dependency-direction]] — Identity types to domain; message knowledge stays in protocol
- 2026-08-05 [[decisions/2026-08-05-grading-facts-not-events]] — The protocol crosses into application as facts, not grading events
- 2026-08-05 [[decisions/2026-08-05-git-retry-scope]] — Git retries lock contention only; path-scoped partial commits
- 2026-08-06 [[decisions/2026-08-06-wire-git-into-the-pipeline]] — The commit rides with the writer; the daily backup asks rather than fires
- 2026-08-06 [[decisions/2026-08-06-container-network-posture]] — In a container the publish address is the control, not the bind address
- 2026-08-06 [[decisions/2026-08-06-record-repository-lock]] — Exclusive record-repo lock lives in `.git/`; measured not to hold on a Docker Desktop bind mount
- 2026-08-06 [[decisions/2026-08-06-one-place-carries-tense]] — The README states build status in one table; everything else is design tense
- 2026-08-06 [[decisions/2026-08-06-markdown-paths-must-exist]] — Path guard over maintained docs only; tree blocks anchored to a real directory
- 2026-08-06 [[decisions/2026-08-06-mcp-read-slice]] — MCP: hand-rolled JSON-RPC over MVC, dual-era (2026-07-28 + handshake), three read tools
- 2026-08-06 [[decisions/2026-08-06-shipped-problem-catalog]] — The catalog is scanned once by us and ships in the jar; one scan ever, not one per user
- 2026-08-07 [[decisions/2026-08-07-heartbeat-behind-the-lock]] — A liveness marker behind the lock, compared for change rather than age, for mounts that report a lock they do not enforce
- 2026-08-07 [[decisions/2026-08-07-server-generated-runners]] — Server generates per-problem runners; refusal before guessing; a language is supported only when its generated runner actually ran
- 2026-08-08 [[decisions/2026-08-08-run-raw-sessions]] — A run's frames are set aside outside the record repository; `rawPath` is null for a run
- 2026-08-10 [[decisions/2026-08-10-sensor-observations]] — The sensor records focused time and a questions-tab visit; measured first, which kept a second dead field out
- 2026-08-10 [[decisions/2026-08-10-guards-must-prove-they-ran]] — Guards match Korean as bytes under a pinned locale, never collapse an error into "clean", and canary themselves before their silence is believed
- 2026-08-10 [[decisions/2026-08-10-state-beside-the-records]] — State moves into the record repository under the lock that already existed; the two credentials stay out, and the server adds the ignore rule itself
- 2026-08-10 [[decisions/2026-08-10-scheduling-is-not-diagnosis]] — The server may compute a review date but never a diagnosis; every item ships the facts that scheduled it, and absence never buys confidence
- 2026-08-11 [[decisions/2026-08-11-korean-for-the-user-facing-half]] — Five user-facing pages get a Korean twin; the drift objection that refused this once is now a guard on a blob hash
- 2026-08-11 [[decisions/2026-08-11-a-grading-is-its-whole-session]] — The capture key digests every frame, not the last one, which was a constant per problem and dropped every submit after the first
- 2026-08-11 [[decisions/2026-08-11-a-failing-run-ends-at-its-result]] — `error` stops ending an algorithm run, and a compile failure with no testcases is a verdict rather than an UNKNOWN
- 2026-08-11 [[decisions/2026-08-11-a-watch-answer-is-not-a-promise]] — `/watch` reports the socket's own verdict, because `started` said the same thing whether the judge confirmed or refused
- 2026-08-11 [[decisions/2026-08-11-a-hole-in-the-record-is-reported-not-filled]] — Orphaned frames are announced at every boot and on `stats`, because a diagnosis over a silently incomplete history is worse than none
- 2026-08-11 [[decisions/2026-08-11-a-pass-belongs-to-its-language]] — `review_queue` and `slow_passes` key on (problem, language); the layout I kept calling the blocker was never one
- 2026-08-11 [[decisions/2026-08-11-the-session-is-checked-where-it-can-answer]] — The socket cannot see an expired cookie, so the server asks the one endpoint measured to answer 200/401
- 2026-08-05 [[decisions/2026-08-05-code-pending-correction-append]] — `codePending` is cleared by appending a correction, not by editing the line (owner-accepted 2026-08-06; changes what a JSONL line means)
- 2026-08-04 [[decisions/2026-08-04-test-environment]] — No Spring in layer tests · integrationTest task split · fixture-file enforcement
- 2026-08-04 [[decisions/2026-08-04-ktor-websocket-client]] — WebSocket client library = Ktor client (CIO engine)

## Concepts
- 2026-08-04 [[concepts/actioncable-broadcast-observation]] — Passive broadcast observation: how it works and its limits
- 2026-08-04 [[concepts/verdict-classification]] — Verdict classification and the silent-failure trap
- 2026-08-05 [[concepts/bom-version-shadowing]] — The dependency you declared is not the one that runs
- 2026-08-05 [[concepts/assumption-vs-measurement]] — How our own claims became "facts", and the four that were caught
- 2026-08-05 [[concepts/orchestrated-implementation]] — Building with supervised workers: three recurring failures and what supervision is for
- 2026-08-11 [[concepts/tests-that-explain-defects]] — When a test goes green around the wrong behaviour and leaves a comment saying why it is correct

## Entities
- 2026-08-04 [[entities/programmers-actioncable]] — What the Programmers judge actually is
- 2026-08-04 [[entities/solved-ac]] — Source of the tag vocabulary
- 2026-08-04 [[entities/baekjoonhub]] — The prior tool this project replaces

## Syntheses
- 2026-08-04 [[syntheses/protocol-reverse-engineering]] — The full protocol-discovery story

## Sources
- 2026-08-04 [[sources/2026-08-04-oss-workflow]]
- 2026-08-04 [[sources/2026-08-04-protocol-reverse-engineering-and-design]]
- 2026-08-04 [[sources/2026-08-04-record-keeping-design]]
- 2026-08-05 [[sources/2026-08-05-capture-pipeline-built-end-to-end]] — The capture half built in one afternoon, and four findings that outlived it
- 2026-08-05 [[sources/2026-08-05-design-review-and-stack-upgrade]]
- 2026-08-06 [[sources/2026-08-06-catalog-runners-and-the-record-repository]] — Our labels over their identifiers, and the day Programmers turned out to have two problem shapes
- 2026-08-07 [[sources/2026-08-07-adversarial-review]] — Seven runners, then four critics: four CRITICAL, and code injection into runners the user executes
- 2026-08-10 [[sources/2026-08-10-sensor-verified]] — The sensor watched to work, the state moved where the design said, and a guard that had never run
- 2026-08-11 [[sources/2026-08-11-backfilling-the-raw-layer]] — Where the transcripts had been going, and what a six-day-late ingest can and cannot recover
- 2026-08-11 [[sources/2026-08-11-capture-defects-found-by-solving]] — Nine capture defects, five of them found only by solving problems in a browser
- 2026-08-11 [[sources/2026-08-11-expiry-has-no-socket-signal]] — An invalid session is confirmed and pinged normally and receives nothing; the socket has no expiry signal at all
