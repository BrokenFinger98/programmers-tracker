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
- 2026-08-11 [[decisions/2026-08-11-a-record-on-one-disk-says-so]] — Startup says how long the records have not left the machine, and why a repository with no remote is not a fault
- 2026-08-12 [[decisions/2026-08-12-the-server-counts-and-names-nothing]] — A tag map makes the types you never met visible; the server writes the denominators and refuses to say which of them is a weakness
- 2026-08-12 [[decisions/2026-08-12-a-cancellation-we-caused-is-not-a-failure]] — Unsubscribing was reported as a dropped connection; the textbook fix would have disabled the reconnect the class exists for, and an existing test caught it
- 2026-08-12 [[decisions/2026-08-12-a-language-is-supported-when-its-failures-are-too]] — Six of seven languages were classified by two patterns written for two others, four of them right only by coincidence; a language now owes a compile-failure fixture
- 2026-08-13 [[decisions/2026-08-13-node-size-is-what-you-solved]] — Obsidian sizes a node by its links, so 510 catalog edges made the biggest node the catalog's rather than the reader's; the tag map is sized by your own problems and "never met" moves to colour
- 2026-08-13 [[decisions/2026-08-13-the-vault-is-not-only-records]] — The daily backup committed a graph setting and nothing else under a message about records; ignoring the editor's directory beats narrowing the net that catches lost ones
- 2026-08-13 [[decisions/2026-08-13-the-server-prepares-the-repository]] — init, seeds and a GitHub token replace bootstrap §2 and the SSH path; private is hardcoded and re-verified, the token is removable after one boot, and the template retires
- 2026-08-13 [[decisions/2026-08-13-the-pointer-is-passed-not-persisted]] — The credential pointer was written into the repository the host shares, so a host push printed `fatal:` over a push that had succeeded; it is passed per command now and persisted nowhere
- 2026-08-13 [[decisions/2026-08-13-a-floor-per-package-and-a-reason-per-exception]] — The coverage gate named a directory and read 13% of it; every package now carries a floor, compiler-generated branches are not counted, and each deviation is an argument rather than a number
- 2026-08-13 [[decisions/2026-08-13-the-statement-travels-with-the-record]] — The records knew how you failed and not what was asked; the statement rides on a fetch we already make, is written once beside the attempts, and never enters this repository
- 2026-08-14 [[decisions/2026-08-14-a-preview-api-under-a-test]] — `Flow.timeout` stays on the observation socket: the acceptance rests on the two tests that pin what it means, and the build reaches zero warnings for the first time
- 2026-08-14 [[decisions/2026-08-14-the-push-waits-for-the-fetch-the-commit-does-not]] — The commit a pass pushed held the verdict and the frames and not the solution; a second push follows the fetch, and the first one stays where it is
- 2026-08-14 [[decisions/2026-08-14-the-seed-ships-in-the-form-its-reader-rewrites-it-to]] — Obsidian strips the dashboard's comments the moment the view renders, which silently froze the seed; it now ships pre-stripped, and a file already equal to ours is adopted
- 2026-08-05 [[decisions/2026-08-05-code-pending-correction-append]] — `codePending` is cleared by appending a correction, not by editing the line (owner-accepted 2026-08-06; changes what a JSONL line means)
- 2026-08-04 [[decisions/2026-08-04-test-environment]] — No Spring in layer tests · integrationTest task split · fixture-file enforcement
- 2026-08-04 [[decisions/2026-08-04-ktor-websocket-client]] — WebSocket client library = Ktor client (CIO engine)
- 2026-09-29 [[decisions/2026-09-29-a-replaced-credential-reopens-the-observation]] — A socket accepted with a dead cookie has no replacement signal either; the heartbeat compares the credential's digest and reopens the observation
- 2026-09-29 [[decisions/2026-09-29-the-sensor-hands-over-the-session]] — The browser is the only party that ever holds a fresh cookie, so the extension posts it to `/session` on two triggers and the manual paste becomes the fallback
- 2026-09-30 [[decisions/2026-09-30-the-registry-has-one-lock]] — `/watch` became one runBlocking per request, so the registry's single-writer assumption went false; every operation is atomic under one lock now
- 2026-09-30 [[decisions/2026-09-30-one-probe-in-flight]] — Each tab's heartbeat probed on its own on a cold cache; the first caller owns the probe and the rest take its answer
- 2026-10-01 [[decisions/2026-10-01-a-failed-run-that-returned-a-result-is-wrong]] — A database run carries no message either way; a failed one that returned a table is a wrong answer, not a result the server could not classify
- 2026-10-07 [[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]] — Recurring mistakes are the AI's diagnosis over repair steps the server serves; every run's code is kept so the corrections exist, and MCP stays read-only
- 2026-10-07 [[decisions/2026-10-07-a-record-is-its-time-and-its-bytes]] — Readers resolve the log per `(ts, captureKey)`; byte-identical gradings stop folding into one (#343)
- 2026-10-07 [[decisions/2026-10-07-database-failures-that-said-something]] — A wrong SQL submit (bare 실패) is WRONG and a run MySQL rejects is COMPILE_ERROR; a rejected submit stays RUNTIME_ERROR, measured on purpose
- 2026-10-07 [[decisions/2026-10-07-every-run-keeps-its-code]] — Each run's code in the problem's runs.jsonl, joined to the log by recordId; codeFetchedAt lets a reader tell a late attachment from the code that ran
- 2026-10-07 [[decisions/2026-10-07-repair-steps-are-served-not-judged]] — repair_steps pairs each non-pass with the next grading in its language and names every missing diff; get_problem(include), stats by part/level; kept code read only from the real problems/; every MCP text under the client's 2,048-character cut; returned deferred
- 2026-10-07 [[decisions/2026-10-07-no-reader-follows-a-link-out-of-problems]] — Every reader under problems/ whose content leaves opens files through one bound, ProblemFiles; the audit found the statement inlined into the pushed README, which the issue had missed, and two link exposures outside its scope
- 2026-10-07 [[decisions/2026-10-07-exam-prep-asks-in-the-open]] — One MCP prompt, `exam_prep`, asks the learner's own model in the open for the naming the tools never do; Claude Code fills its arguments by position, split on spaces, so `language, since, part` is an interface, `since` is checked, a part cut at its first space is left for the model to match against the `stats` part keys, and an empty answer under a scope is said not to be clean
- 2026-10-08 [[decisions/2026-10-08-reconcile-never-stages-the-state-directory]] — Five layers keep the push token out of what the tracker commits and pushes, after two adversarial reviews turned each path guard, and then a search for the stored value alone, against itself: a pathspec that leaves the `.ps` entry and everything under it out in any ASCII case; no commit, push or state write while `.ps` is an alias, holds a link or holds anything git tracks (listed by its exact name, since Linux over a macOS mount reports a folded alias as `.ps`); state written by replacement, never through a link; a content gate that searches the working tree, the index and every outgoing commit for the stored token and GitHub's token shapes; and one named branch pushed to one named remote, replace refs off. Costs kept with numbers — a first push of 1,660 commits spends 50 s searching — and messages are not searched (#360)

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
- 2026-10-08 [[entities/claude-code-mcp-client]] — What the client the owner uses does with what the server sends: the 2,048-character cut, positional prompt arguments, a codec with no defaults, a display name that is not the command, and how each was learned

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
- 2026-08-12 [[sources/2026-08-12-the-improvement-loop-turns-inward]] — The guard caught its author, and two defects were found protecting each other
- 2026-08-12 [[sources/2026-08-12-every-language-end-to-end]] — Every supported language broken and passed on purpose; the prediction said four defects and the wire said one, plus three coincidences nothing had named
- 2026-08-12 [[sources/2026-08-12-two-workers-that-never-started]] — The orchestration detour the build night left out: a composer that splits multi-line specs, a worker that never answered, and the stopping rule
- 2026-08-12 [[sources/2026-08-12-clean-slate-verification]] — Fifteen records from one build against an empty repository, and the browser mechanic that had cost three sessions of guessing
- 2026-08-13 [[sources/2026-08-13-the-map-that-linked-to-nothing]] — Three defects in one feature and none found by a test; a true sentence pinned the worst of them
- 2026-08-13 [[sources/2026-08-13-the-tally-that-counted-runs]] — The vault verified against the running server, and two MCP tools that answered 8 and 15 for the same problem
- 2026-08-13 [[sources/2026-08-13-the-map-becomes-a-workspace]] — The owner starts using the vault: the tag-edge reversal, four run-is-not-attempt sites, the UTC clock, the seeded dashboard, and the record finally saying algorithm or database
- 2026-08-14 [[sources/2026-08-14-the-night-the-records-learned-the-question]] — Eleven PRs, seven defects, none found by reading code — and three findings that refuted something I had asserted within the hour
- 2026-08-14 [[sources/2026-08-14-the-warnings-and-what-was-under-them]] — The owner's own build output was the finding; one warning hid another, and the safe call was standing on a live untested fallback
- 2026-08-14 [[sources/2026-08-14-the-clean-slate]] — Recovered from the inbox: the seed ledger's third state, the two editor directories, the statement inlined, and the wipe that made a first boot possible
- 2026-08-14 [[sources/2026-08-14-the-first-run-test-and-what-it-found]] — Two Lv0 problems solved from a blank vault, the last coverage exemption retired, and two defects only a first run could surface
- 2026-08-19 [[sources/2026-08-19-the-vault-reset-and-the-guard-that-was-rerun]] — Recovered from the inbox: the vault wiped to zero, the owner's own timer kept, a tracked .iml, and the guard I had rerun until it passed
- 2026-08-28 [[sources/2026-08-28-the-first-real-record]] — Recovered from the inbox: the first real record was fine; 188 hours "Elapsed", raw HTML in a stdin statement, and a runner instruction that needed JDK 22
- 2026-09-29 [[sources/2026-09-29-a-session-expiry-and-the-socket-that-looked-alive]] — A replaced cookie healed the probe and not the observation; two solves lost with every indicator green; the first cut broken by its own race test's silence
- 2026-09-30 [[sources/2026-09-30-the-sensor-hands-the-session-over]] — Trigger 1 measured at a real sign-in, a hundred identical cookie sets in thirty seconds, JSON null in the credential file, and two defects shipped beside the work
- 2026-10-01 [[sources/2026-10-01-a-wrong-query-and-the-purple-question-mark]] — A failing SQL run carries a table and no message; three false `?` alarms in an afternoon, and the field that fixed them
- 2026-10-03 [[sources/2026-10-03-the-fix-measured-and-an-sql-error-frame]] — The fix verified live on a wrong run; the first SQL-error frame measured, carrying MySQL's error tuple and staying UNKNOWN
- 2026-10-06 [[sources/2026-10-06-the-history-that-folded]] — Every reader folds distinct gradings that share a capture key: 10 runs and 3 submits recorded, 5 and 1 visible (#343)
- 2026-10-07 [[sources/2026-10-07-repairs-not-verdicts]] — The mistake-patterns design and four PRs verified live; a design mark that could never be set, a code-read bound in three rounds, and the 2,048-character cut a 3,000-character test had passed
- 2026-10-07 [[sources/2026-10-07-the-readers-that-followed-links]] — The #354 audit found the worst reader the issue missed; two review rounds, a four-hour outage, the live check, the client read for #364, and a rule 117 commits broke
- 2026-10-08 [[sources/2026-10-08-the-prompt-only-the-owner-can-run]] — `exam_prep` built overnight; a command name corrected twice, the second time by running the client's own parser; server side verified live, the owner's run pending
- 2026-10-08 [[sources/2026-10-08-the-first-exam-prep-run]] — The owner's first `exam_prep` run meets spec §6. 58 of 63 steps had no diff, so the patterns came from the judge's output, which the text now names as the fallback (#370)
