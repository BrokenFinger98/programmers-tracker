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
- 2026-08-04 [[decisions/2026-08-04-test-environment]] — No Spring in layer tests · integrationTest task split · fixture-file enforcement
- 2026-08-04 [[decisions/2026-08-04-ktor-websocket-client]] — WebSocket client library = Ktor client (CIO engine)

## Concepts
- 2026-08-04 [[concepts/actioncable-broadcast-observation]] — Passive broadcast observation: how it works and its limits
- 2026-08-04 [[concepts/verdict-classification]] — Verdict classification and the silent-failure trap
- 2026-08-05 [[concepts/bom-version-shadowing]] — The dependency you declared is not the one that runs
- 2026-08-05 [[concepts/assumption-vs-measurement]] — How our own claims became "facts", and the four that were caught
- 2026-08-05 [[concepts/orchestrated-implementation]] — Building with supervised workers: three recurring failures and what supervision is for

## Entities
- 2026-08-04 [[entities/programmers-actioncable]] — What the Programmers judge actually is
- 2026-08-04 [[entities/solved-ac]] — Source of the tag vocabulary
- 2026-08-04 [[entities/baekjoonhub]] — The prior tool this project replaces

## Syntheses
- 2026-08-04 [[syntheses/protocol-reverse-engineering]] — The full protocol-discovery story

## Sources
- 2026-08-04 [[sources/2026-08-04-protocol-reverse-engineering-and-design]]
- 2026-08-04 [[sources/2026-08-04-record-keeping-design]]
- 2026-08-04 [[sources/2026-08-04-oss-workflow]]
- 2026-08-05 [[sources/2026-08-05-design-review-and-stack-upgrade]]
