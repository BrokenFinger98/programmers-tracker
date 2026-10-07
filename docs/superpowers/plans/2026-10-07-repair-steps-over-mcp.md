# Repair Steps over MCP — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An AI can ask for every correction the learner made after a failed grading — the failed grading, the attempt that followed it, and the diff of the code between them — and for the code behind one problem's gradings, and for per-part and per-level progress counts. The server serves facts and counts; it names no mistake.

**Architecture:** Part 4.3 of `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md`. Two pure calculators in `domain/calc` (`CodeTimeline` attaches kept code and flags late attachments; `RepairSteps` pairs consecutive gradings per language and diffs them), with the existing LCS diff moved from `adapter/store` into `domain/calc` so a calculator may use it. A new outbound port `GradingCodes` (application) is implemented by `FileGradingCodes` (store), reading `runs.jsonl` and `attempts/`. `RecordQuery` assembles; the MCP adapter gains `repair_steps`, `get_problem(include)`, and `stats` grouped by `part`/`level` with problem counts. MCP stays read-only.

**Tech Stack:** Kotlin (JVM 25), Spring Boot 4, kotlinx.serialization, JUnit 5 + Kotest assertions, Gradle Kotlin DSL, Kover branch floors (`domain/calc` 95%).

---

## Measured before planning — and what it changed

Read from the live record repository (`/Users/yu-sun00/Desktop/ps-records`) on 2026-10-07, resolving the log per `(ts, captureKey)` exactly as `RecordHistory` does:

- **131 gradings, 65 candidate steps** (a grading that did not pass, followed by another grading of the same problem in the same language). By the `from` side: 34 WRONG run→run, **23 unresolved (`verdict` absent, `UNKNOWN`) run→run**, 2 COMPILE_ERROR run→run, 2 WRONG run→submit, 2 unresolved submit→run, 1 COMPILE_ERROR run→submit, 1 WRONG submit→run. **Zero submit→submit.**
  → *Unresolved gradings must start steps* (25 of 65 would vanish otherwise — they are the SQL failures recorded before #350 fixed their classification, i.e. exactly lesson 273711's class of evidence). See decision D3.
  → *Almost every historical step involves a run*, and runs have code only from 2026-10-07. The first live acceptance therefore needs a freshly solved problem; the historical answer will be dominated by `noDiff: "fromCodeUnknown" / "codeUnknown"`. This is the spec's §7 accepted cost, now with a number.
- **36 submits, all 36 `codePath`s point into `attempts/` and all 36 files are readable.** Submit code can be read from the path the record carries; no layout lookup is needed.
- **`runs.jsonl`: one file (lesson 59036), 3 lines.** Every `recordId` joined a log record; code attached 0.27 / 0.30 / 0.38 s after each record; **none late** by the `codeFetchedAt > next ts` rule. The join key works on real data.
- **Part axis:** 34 of 36 submits are `SELECT`. `stats(groupBy=part)` is meaningful on this data; `groupBy=level` spreads 22/8/4/2 over levels 1/2/4/0.
- **The MCP `instructions` string is 2,618 characters** against `McpInstructionsTest`'s 3,000 cap. The additions in Task 10 bring it to ~2,905 — inside the cap with ~95 characters left for part 4.4. See D12. *(Superseded in review: the client cuts at 2,048 characters, so the budget became 2,000 as sent — see the corrected D12.)*

## Decisions made while planning

The spec left these open. Each is a choice the controller should review before execution.

| # | Decision | Reason |
|---|---|---|
| D1 | **Move `UnifiedDiff` from `adapter/store/CodeArtifacts.kt` (private) to `domain/calc/UnifiedDiff.kt` (public object)**, minus its logger; `CodeArtifacts` keeps the "too large" warning by asking `UnifiedDiff.fits` first. `CodeArtifacts.MAX_DIFF_LINES`/`MAX_DIFF_INPUT_LINES` stay as aliases so `CodeArtifactsTest` is untouched. | `domain` imports nothing, so `RepairSteps` cannot call an adapter. The alternatives were worse: computing the diff in the adapter splits the calculator (it must still compare code to decide "identical code makes no step"), and duplicating the LCS would give attempts and repair steps two diffs that format differently. The diff was always a pure function of two texts. It is a move, not a rewrite — no behaviour changes. |
| D2 | **`RepairSteps` takes one problem's whole timeline (all languages), not one language.** It pairs per language internally. A separate calculator `CodeTimeline` attaches code and computes `late` first. | The settled lateness rule compares `codeFetchedAt` with **the next record on the same problem** — in any language. A calculator handed one language cannot see that record. |
| D3 | **"Failed" = verdict is not `PASS`, including an absent verdict** (`UNKNOWN`/`INCOMPLETE`). The `from` side always carries `outcome`, and `verdict` only when resolved. | 25 of the 65 measured candidate steps start at an unresolved grading — the SQL failures #350 fixed for new records only. Dropping them hides the evidence the design was written for; labelling them a verdict would invent one. Absent verdict + `outcome` says exactly what is known. |
| D4 | **Identical code makes no step — unless a side's code is late.** A late side keeps the step, with `noDiff: "sameCode"`. Code is compared after trimming trailing newlines. | A late attachment *is* the later grading's code, so "identical" there is the signature of the race, not of "nothing changed"; dropping it would be the silent drop the settled facts forbid. Trailing-newline trim: `runs.jsonl` keeps code as fetched while `attempts/NNN.*` is written with exactly one trailing newline (`CodeArtifacts.normalized`), so the same code read from the two places would otherwise differ. |
| D5 | **A step without a diff names why, in one field `noDiff`**: `fromCodeUnknown` · `toCodeUnknown` · `codeUnknown` · `sameCode` · `tooLarge`. Exactly one of `diff`/`noDiff` is present. Lateness is a per-side boolean **`codeLate: true`**, present only when the check found it late. | One enum covers every reason a reader would otherwise have to guess (including the 2000-line LCS limit, which today yields `null` silently). `codeLate` sits on the side it describes. Its absence means "not found late" — a submit has no fetch time and is never checked; the tool description says so. |
| D6 | **Submit code gets no lateness check.** | Submit code is attached by the same fetch, but nothing records when; `attempts/NNN.*` has no `codeFetchedAt`. Inventing one (file mtime) would be a guess. Accepted cost, in the ADR. |
| D7 | **Diff headers are `--- a/from` / `+++ b/to`.** | The record ids are already on both sides; repeating 50-character ids in the header adds weight, and a path (`attempts/002.java`) does not exist for a run. |
| D8 | **`since` bounds the `to` side's `ts`; results are newest first by `to.ts`; pairing happens on the full history *before* filtering.** | The correction is the event a pre-exam session asks about. Filtering first would orphan the first step after `since` from the failure that started it. |
| D9 | **`errorText` is returned in full on `from`** (no truncation). `limit` bounds the answer size — and since the quality review of Tasks 3–4 it always does: **default 20** when none is given, with `total` and `truncated` in the answer (see "Changed in review"). | It is the step's core evidence and the spec's example carries it. The diff is already capped at 400 lines by `UnifiedDiff`. Without a default, one argument-less call returned every step on record, which grows without bound; the default is the cap, and the answer says when it applied. Record it in the Task 11 ADR. |
| D10 | **`get_problem(include)` enriches the existing `submissions` items rather than adding a parallel `runs` array.** `code` → `code` on each submit item; `runs` → on each run item `code`, `codeFetchedAt`, `codeLate`, and `diffFromPrevGrading` (diff from the previous grading in the same language, any action) or `noDiff`. The first grading in a language has neither. Both a JSON array and a bare string are accepted for `include` (lenient about type, strict about values, as `lessonId` already is). | The array already holds every run; a second array would duplicate every run record. `diffFromPrevGrading` is named apart from the existing submit-to-submit `diffFromPrev` because the two compare against different neighbours. |
| D11 | **`stats` progress fields (`attempted`, `passed`, `passedFirstSubmit`, `runsBeforePass`) appear only on `part` and `level` buckets**, computed by a new pure `ProblemProgress` inside the same `SubmissionTally` call; they count **problems (lessonId), not (problem, language) pairs**; runs in any language before the first passing submit count; `runsBeforePass` is the median over passed problems (a `Double`, absent when nothing in the bucket passed). `count` keeps meaning submits. | On `verdict` buckets these numbers mean nothing (a problem "passed" inside the WRONG bucket), and on `problem` buckets they repeat `list_problems`. The spec's "each bucket" is read as each bucket of the new groupings — flagged below. Problems, not pairs, because `part`/`level` are properties of the problem and `stats(problem)` already counts across languages. |
| D12 | **Every MCP text stays within 2,000 characters as sent** — the server instructions and each tool description, shared suffixes included, pinned by `McpInstructionsTest` and `McpToolCatalogTest`. *(Corrected in review: this row first kept the instructions' 3,000 cap, with ~2,905 used and ~95 left for part 4.4.)* | Claude Code cuts server instructions and every tool description at 2,048 characters and keeps the head (CHANGELOG 2.1.84 "capped at 2KB"; 2.1.280 `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` "to change the 2,048-character cap"; verified on 2.1.285 with the variable unset), so the 3,000 cap passed while the text was cut — the live instructions, 2,607 characters since #287, arrive ending "…[truncated]". 2,000 leaves 48 for a client that counts a little differently. As built: instructions 1,973 (27 left), get_problem 1,983, repair_steps 1,983, stats 1,788, slow_passes 1,356, review_queue 1,084, submissions 804, list_problems 691. Raising a budget to make room is still the drift it exists to stop, so part 4.4 carries its guidance in the `exam_prep` prompt itself, not in the instructions. |
| D13 | **`returned` (SQL returned tables from `.ps/raw/`) is deferred to its own plan.** | Not cheap: the frames live outside the record repository behind `RawSessionLog`; the table is double-encoded inside `returned_rows` (fixture `sql-run.jsonl`), so reading it means protocol parsing on the read path — protocol types must not leave `protocol` (dev rules §1), so it needs a new parse-side fact type; it is Programmers' data needing a truncation rule; and no step of 4.4's prompt depends on it. YAGNI. |
| D14 | **Steps are labelled with a `ProblemLabel` built like `RecordQuery.problem()` builds metadata** — newest record carrying each field. `part` filtering uses that label. | One problem's early records may lack catalog fields; the newest-carrying rule is already the repository's answer and keeps `part` filtering stable per problem. |
| D15 | **`FileGradingCodes` resolves `codePath` through a new `RecordLayout.recordFile(relative)`** that refuses any path leaving the repository root. | The log is ours, but the MCP read path must not follow `../` on the strength of a line someone could have edited. |
| D16 | **`runs.jsonl`'s line type becomes `internal data class RunLine`** in `RunLog.kt`, decoded by `FileGradingCodes`; the read side never holds a `RunLog`. | One definition of the four-key shape; and `McpConfiguration`'s rule is that nothing on the read side can append. |

## Found in the spec — contradictions and gaps

1. **§4.3 "one problem's gradings in one language" vs. the lateness rule** (next record on the same problem, any language). Resolved by D2.
2. **§4.3 "identical code does not make a step" vs. §4.2's late attachment**, which makes two different runs *look* identical. Resolved by D4.
3. **§4.3 `stats`: "Each bucket adds attempted, passed, …"** — meaningless on `verdict`/`problem` buckets. Resolved by D11; the spec text should say "each `part`/`level` bucket" (Task 11).
4. **§4.4 step 1 asks "where passing took the most runs and submits"** — there is no submits-before-pass field in §4.3's list. `count / attempted` approximates it (but `count` includes submits after the pass). Not added (YAGNI); flag for the 4.4 plan.
5. **ADR `2026-10-07-mistake-patterns-are-diagnosed-not-stored` still says "the design marks such runs `codeUncertain`"** — superseded by `codeFetchedAt` (4.2) and `codeLate` (here). Task 11 amends it.
6. **§4.4 step 5 / the instructions: "records before 2026-10 have no run code"** — true of runs only. Submit code has always been in `attempts/`, so older run-free steps *do* get diffs (none exist in the live log today, but the wording must not say "no code"). The instructions text in Task 10 says "Run code".
7. **§4.3 `get_problem` "`runs` (the run timeline …)"** is ambiguous between a new array and enriched items. Resolved by D10.
8. **`returned`** — deferred, D13. §4.2 already says "MCP may read them from there (4.3)"; Task 11 updates that pointer to "a later plan".

## Changed in review (controller notes — apply in the named task)

- **Task 8 — response size (quality review of Tasks 3–4, Important 3).** `repair_steps` gets a default `limit` of 20 when none is given, and the answer carries `total` (steps matching before the limit) and `truncated: true` when `total > count`, so the reader knows the list is cut. D9 stands for `errorText`; the limit is the size control and it now always applies.
- **Task 8 — truncated diffs (Important 4).** `UnifiedDiff` caps output at 400 lines with a text marker. Serialize `diffTruncated: true` on a step whose diff hit the cap (decide it via a `UnifiedDiff` helper or by the marker, in one place), so a reader never has to parse the marker.
- **Task 6 — `ProblemLabel.of(records)`** now exists in the domain (fix commit after ec79875); `RecordQuery.labelOf` must use it rather than re-implementing the rule.
- **Task 8 — argument validation.** `RepairStepFilter` is strict (limit > 0, language/part not blank); `McpToolInvoker` must map bad arguments to INVALID_PARAMS before constructing it.
- **Task 9 — order.** `RepairSteps.transitions` returns time order across languages (fix commit after ec79875).
- **Task 6 — from the second quality review of Tasks 3–4 (Minors).** (a) Move `ProblemLabel` (and its companion) out of `RepairStepFilter.kt` into its own `domain/calc/ProblemLabel.kt` — `TallyGroup` now depends on it, and its test already lives in `ProblemLabelTest.kt`. (b) `RecordQuery.labelOf` uses `ProblemLabel.of`; `RecordQuery.problem()` keeps its own `newest(...)` for `acceptanceRate`/`tags` (not in a label) — leave it, out of scope. (c) Add a `ProblemLabelTest` case pinning the KDoc's tie claim: two records with the same `ts`, handed in newest-first (as `history()` does), take the first one's field. (d) `CodeTimeline`'s KDoc says `RecordQuery` passes log order; `history()` is newest first, so Task 6 reverses it (`asReversed()`) — make the KDoc say exactly that.
- **Tasks 8–9 — from the quality review of Tasks 5–6 (applied in the fix commits after feddcbd).** `RecordLayout.recordFile` is bounded at `<root>/problems` (the repository root also holds `.ps/git-credentials` — the push token — `.ps/raw/`, `.git/`, `log/`); D15 in the Task 11 ADR must say so. `RepairStepFilter.applied` and `RecordQuery.repairSteps` return `RepairStepPage(steps, total)` with `isTruncated()`; **Task 8 applies the default limit 20 in the MCP adapter** and serializes `total`/`truncated` from the page. `CodedProblem.codeOf` is renamed `gradingOf`; `RecordQuery.codedProblem` takes one problem's records (`ProblemHistory.submissions`) so **Task 9 reads the log once**: `val problem = query.problem(id); val coded = query.codedProblem(problem.submissions)`.
- **Task 11 ADR — the code-read bound (commits feec25a, 2cde57e, 4980e56).** Kept submit code is read only from a regular file whose real path lies under `<real records root>/problems` — lexically bounded in `RecordLayout.recordFile`, then by real path in `FileGradingCodes.submitted`, and a linked `problems` directory is never followed (git stores links, so one can arrive by clone or pull; the root also holds `.ps/git-credentials`, the `/watch` token and raw frames). Accepted cost: a deliberately linked `problems` directory yields no kept code. The same exposure in the already-shipped statement reader is #354, fixed after this branch with a shared helper.
- **Task 11 — a doc the plan missed (found 2026-10-07 by searching every `*.md` that names an MCP tool).** `docs/bootstrap.md` §"What is not there yet" says "The MCP server exposes six tools … `submissions`, `get_problem`, `stats`, `list_problems`, `review_queue` and `slow_passes`": make it seven and add `repair_steps`; the same sentence in `docs/bootstrap.ko.md`, then its `translated-from` hash (`git hash-object docs/bootstrap.md`). Checked and unaffected: `extension/README*` (mentions `review_queue` only as a consumer of sensor data), `CONTRIBUTING*`, the vault README (layout already carries `runs.jsonl` since #352), `docs/bootstrap.md`'s layout block (it shows what one submit writes, which is unchanged).
- **Task 11 — wording and numbers as built (spec review of Tasks 9–10).** (a) Wherever the plan's `mcp.md` / `mcp.ko.md` drafts say "Run code is kept from 2026-10-07", write what the server says: run code is kept **by tracker versions from 2026-10-07 on** (Korean: 2026-10-07 이후 버전의 트래커가 실행 코드를 보관). Never 2026-08-07. (b) **Superseded by the client cap (quality review of Tasks 9–10, commits 27cc839/6ab8e19/1545753):** Claude Code cuts MCP server instructions and every tool description at **2,048 characters** (CHANGELOG 2.1.84 "capped at 2KB"; 2.1.280 `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` "to change the 2,048-character cap"); the old `< 3000` test was a false assurance — the live instructions already arrived cut ("…[truncated]"). The budget is now **≤ 2,000 characters as sent** (suffixes included), pinned by tests for the instructions and every tool. As built: instructions 1,994 (**6 left**), get_problem 1,983, repair_steps 1,983, others 691–1,788; eccf93a then brought the instructions to 1,973 (27 left). Correct D12's row and record the cap, the measurement and the evidence in the ADR. Part 4.4 must therefore put its guidance in the `exam_prep` prompt itself (prompts are a separate capability), not in the instructions. (c) The `runsBeforePass`-0 caveat lives in the `stats` description only (the controller read "description / instructions" as either; the model reads field semantics from the description and the instructions budget is kept for 4.4). (d) `get_problem(include=runs)` has no limit: record it as an accepted cost in the ADR with the size estimate from the Tasks 9–10 quality review; `repair_steps` (default 20) is the bounded cross-problem path, `include=runs` drills into one problem. (e) Keep the `docs/mcp.md` heading "What is not built" — `McpToolCatalog`'s KDoc points at it and guards.sh checks paths, not headings.
- **Deferred (M5, cost).** `repairSteps` without `lessonId` reads every problem's code before filtering, and `RecordLayout.existingDirectoryOf` lists `problems/` per call (O(P²) — 676 listings at 26 problems, ~475k at the full 689-problem catalog). Not needed at today's scale; if it is ever needed, skip problems that cannot yield a step before reading code (a `RepairStepFilter.mayAdmit`) and cache the directory listing per call.
- **Tasks 7/10 — descriptions.** `runsBeforePass` of 0 can mean "no runs recorded" for passes before run capture existed (2026-08-07) or with `incompleteHistory`; say so in the `stats` description / instructions.

## Ground rules for whoever executes this

- Read `CLAUDE.md` and `docs/development-rules.md` first. Binding: TDD pairs (every new production `.kt` has a new test `.kt` in the same PR), zero-mock unit tests for `domain/calc`, three-layer tests, object mothers, English-only artifacts, ktlint (`max_line_length` 120), methods ≤ 10 lines, early return, issue-first branch, squash PR.
- Gates before the PR, all must exit 0: `./scripts/check.sh`, `./scripts/test.sh`, `./scripts/build.sh`, and `./gradlew verifyBranchCoverage` (CI runs it; `domain/calc` floor is 95%). The push hook runs `scripts/guards.sh` (no Hangul in comments; Korean twins' `translated-from` hash) and blocks a push with no `docs/llm-wiki/` change.
- Run one class: `./gradlew test --tests 'com.brokenfinger.tracker.domain.calc.RepairStepsTest'`.
- Korean string literals in tests are fine (`"두 수의 곱 구하기"` is the fixture title); Hangul in a **comment** fails `guards.sh`.
- Live server: Docker container on `127.0.0.1:1619`. After merge: `docker compose build && docker compose up -d --force-recreate`.
- Branch: `feat/ISSUE-repair-steps-over-mcp` from a fresh `main` (replace `ISSUE` with the number `/issue` creates; no `#` in branch names). Commit trailers: `Refs #353` until the last commit, which says `Closes #353`.

## File map

| File | Change | Task |
|---|---|---|
| `src/main/kotlin/com/brokenfinger/tracker/domain/calc/UnifiedDiff.kt` | new — moved from `CodeArtifacts.kt` | 1 |
| `src/test/kotlin/com/brokenfinger/tracker/domain/calc/UnifiedDiffTest.kt` | new | 1 |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/CodeArtifacts.kt` | use `domain.calc.UnifiedDiff`; delete private copy | 1 |
| `src/main/kotlin/com/brokenfinger/tracker/domain/calc/ProblemProgress.kt` | new | 2 |
| `src/test/kotlin/com/brokenfinger/tracker/domain/calc/ProblemProgressTest.kt` | new | 2 |
| `src/main/kotlin/com/brokenfinger/tracker/domain/calc/SubmissionTally.kt` | `PART`, `LEVEL`, `countsProblems()`, `TallyBucket.progress` | 2 |
| `src/test/kotlin/com/brokenfinger/tracker/domain/calc/SubmissionTallyTest.kt` | new cases; wire names | 2 |
| `src/main/kotlin/com/brokenfinger/tracker/domain/calc/CodeTimeline.kt` | new — `KeptCode`, `CodedGrading`, `CodeTimeline` | 3 |
| `src/main/kotlin/com/brokenfinger/tracker/domain/calc/RepairSteps.kt` | new — `NoDiff`, `Transition`, `RepairSteps` | 3 |
| `src/test/kotlin/com/brokenfinger/tracker/support/fixtures/RepairStepFixtures.kt` | new — `aRun`, `aSubmit`, `aKeptCode`, `aCodedGrading` | 3 |
| `src/test/kotlin/com/brokenfinger/tracker/domain/calc/CodeTimelineTest.kt` | new | 3 |
| `src/test/kotlin/com/brokenfinger/tracker/domain/calc/RepairStepsTest.kt` | new | 3 |
| `src/main/kotlin/com/brokenfinger/tracker/domain/calc/RepairStepFilter.kt` | new — `ProblemLabel`, `LabelledStep`, `RepairStepFilter` | 4 |
| `src/test/kotlin/com/brokenfinger/tracker/domain/calc/RepairStepFilterTest.kt` | new | 4 |
| `src/main/kotlin/com/brokenfinger/tracker/application/GradingCodes.kt` | new port | 5 |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/FileGradingCodes.kt` | new adapter | 5 |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/store/FileGradingCodesTest.kt` | new | 5 |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/RunLog.kt` | `Line` → `internal RunLine` | 5 |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/store/RecordLayout.kt` | `recordFile(relative)` | 5 |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/store/RecordLayoutTest.kt` | three tests | 5 |
| `src/main/kotlin/com/brokenfinger/tracker/application/RecordQuery.kt` | `repairSteps`, `codedProblem`, `CodedProblem`, `codes` port | 6 |
| `src/test/kotlin/com/brokenfinger/tracker/application/RecordQueryTest.kt` | layer tests | 6 |
| `src/test/kotlin/com/brokenfinger/tracker/support/fixtures/RecordRepositoryFixtures.kt` | `withRunCode`, `withSubmitCode`, real `GradingCodes` | 6 |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/config/McpConfiguration.kt` | wire `FileGradingCodes` | 6 |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpToolInvoker.kt` | stats progress; `repair_steps`; `include` | 7, 8, 9 |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpToolCatalog.kt` | stats description; `repair_steps`; `include` schema | 7, 8, 9 |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpRecordJson.kt` | `repairSteps`; `problem(…, coded, include)` | 8, 9 |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/McpToolInvokerTest.kt` | contract tests | 7, 8, 9 |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/McpToolCatalogTest.kt` | seven tools; schemas | 8, 9 |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/McpRecordJsonTest.kt` | repair-step JSON | 8 |
| `src/main/kotlin/com/brokenfinger/tracker/adapter/mcp/McpDispatcher.kt` | `INSTRUCTIONS` | 10 |
| `src/test/kotlin/com/brokenfinger/tracker/adapter/mcp/McpInstructionsTest.kt` | one test | 10 |
| `docs/mcp.md`, `docs/mcp.ko.md`, `README.md`, `README.ko.md` | seven tools; new section; twin hashes | 11 |
| `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md` | §4.2 pointer, §4.3 "as built", §6 row | 11 |
| `docs/llm-wiki/wiki/decisions/2026-10-07-repair-steps-are-served-not-judged.md` | new ADR | 11 |
| `docs/llm-wiki/wiki/decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored.md` | `codeUncertain` → `codeLate` note | 11 |
| `docs/llm-wiki/index.md`, `.harness/state/progress.md` | entries | 11 |

## Task order and parallelism

```
Task 0 (issue, branch)
 ├─ Task 1  UnifiedDiff → domain/calc ──────┐
 ├─ Task 2  ProblemProgress + PART/LEVEL ──┐│
 │                                          │└─ Task 3  CodeTimeline + RepairSteps
 │                                          │     ├─ Task 4  RepairStepFilter ──┐
 │                                          │     └─ Task 5  port + FileGradingCodes ─┤
 │                                          │                                       └─ Task 6  RecordQuery + wiring
 │                                          └─ Task 7  MCP stats part/level                 │
 │                                                  └──────────────── Task 8  MCP repair_steps (needs 6, 7)
 │                                                                       ├─ Task 9  get_problem include
 │                                                                       └─ Task 10 instructions
 └──────────────────────────────────────────────────────────────────────── Task 11 docs (needs all) → Task 12 PR
```

- **Disjoint file sets, may run in parallel:** {1, 2}; {4, 5} (after 3); {7} with any of {3, 4, 5, 6}; {9, 10} (after 8).
- **Must be sequential:** 7 → 8 → 9 (all edit `McpToolInvoker.kt`/`McpToolCatalog.kt` and their tests); 3 after 1 (`RepairSteps` imports `UnifiedDiff`); 6 after 4 and 5.
- Parallel subagents must each work in **their own worktree** (two Gradle builds in one `build/` directory corrupt each other) and the controller merges. If that is not set up, run the tasks in numeric order.

---

### Task 0: Issue and branch

- [x] **Step 1:** `/issue` — title `feat(mcp): repair steps, problem code and progress counts over MCP`, body: link the spec §4.3, list D13 (`returned` deferred). Note the number as `ISSUE`.
- [x] **Step 2:** `git switch main && git pull --ff-only && git switch -c feat/ISSUE-repair-steps-over-mcp`.

---

### Task 1: The diff becomes a domain calculator (D1)

**Files:** create `domain/calc/UnifiedDiff.kt`, test `domain/calc/UnifiedDiffTest.kt`; modify `adapter/store/CodeArtifacts.kt`.

- [x] **Step 1: Failing test** — `src/test/kotlin/com/brokenfinger/tracker/domain/calc/UnifiedDiffTest.kt`:

```kotlin
package com.brokenfinger.tracker.domain.calc

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Zero mocks (dev rules §6.1). One implementation serves the attempt diffs written into records and
 * the repair-step diffs served over MCP, so the two cannot format a change differently.
 */
class UnifiedDiffTest {
    @Test
    fun `a replaced line reads as a removal then an addition, with context around it`() {
        val diff = UnifiedDiff.of(listOf("a", "b", "c"), listOf("a", "x", "c"), "from", "to")

        diff shouldBe "--- a/from\n+++ b/to\n@@ -1,3 +1,3 @@\n a\n-b\n+x\n c"
    }

    @Test
    fun `identical lines have no diff`() {
        UnifiedDiff.of(listOf("a", "b"), listOf("a", "b"), "from", "to").shouldBeNull()
    }

    @Test
    fun `an empty old side is all additions`() {
        UnifiedDiff.of(emptyList(), listOf("a", "b"), "from", "to") shouldBe
            "--- a/from\n+++ b/to\n@@ -0,0 +1,2 @@\n+a\n+b"
    }

    @Test
    fun `an empty new side is all removals`() {
        UnifiedDiff.of(listOf("a", "b"), emptyList(), "from", "to") shouldBe
            "--- a/from\n+++ b/to\n@@ -1,2 +0,0 @@\n-a\n-b"
    }

    @Test
    fun `changes far apart are separate hunks`() {
        val old = (1..10).map { "line $it" }
        val new = listOf("first") + old.subList(1, 9) + "last"

        val diff = UnifiedDiff.of(old, new, "from", "to")!!

        diff.lines().count { it.startsWith("@@") } shouldBe 2
    }

    @Test
    fun `a diff longer than the cap is cut off and says so`() {
        val diff = UnifiedDiff.of(emptyList(), (1..500).map { "n$it" }, "from", "to")!!.lines()

        diff.size shouldBe UnifiedDiff.MAX_LINES + 1
        diff.last() shouldBe "... diff truncated at ${UnifiedDiff.MAX_LINES} lines"
    }

    @Test
    fun `a side too large to diff cheaply yields no diff`() {
        val huge = List(UnifiedDiff.MAX_INPUT_LINES + 1) { "x$it" }

        UnifiedDiff.fits(huge, emptyList()) shouldBe false
        UnifiedDiff.fits(emptyList(), huge) shouldBe false
        UnifiedDiff.of(huge, emptyList(), "from", "to").shouldBeNull()
    }
}
```

- [x] **Step 2:** `./gradlew test --tests 'com.brokenfinger.tracker.domain.calc.UnifiedDiffTest'` → FAIL (unresolved `UnifiedDiff`; the store's copy is `private`).

- [x] **Step 3: Create** `src/main/kotlin/com/brokenfinger/tracker/domain/calc/UnifiedDiff.kt`. Cut the whole `private object UnifiedDiff { … }` from the bottom of `CodeArtifacts.kt` and paste it here, then make exactly these changes: `private object` → `object`; delete the `logger` field; add the two constants and `fits`; replace `tooLarge` with `fits`. The resulting file:

```kotlin
package com.brokenfinger.tracker.domain.calc

/**
 * A minimal unified diff over lines, backed by an LCS table.
 *
 * Lived privately in `adapter/store/CodeArtifacts.kt` until repair steps needed it
 * (spec 2026-10-07 §4.3): it was always a pure function of two texts, and [RepairSteps] — a
 * calculator that may import nothing outside the domain — must produce the same diff the attempt
 * files carry, not a second one that formats a change differently.
 *
 * Hand-written rather than taken as a dependency: two solution files of a few dozen lines are
 * all this will ever see, and a diff library would buy supply-chain surface for nothing.
 */
object UnifiedDiff {
    /** The diff is inlined into records and answers, so a long one is cut off here. */
    const val MAX_LINES = 400

    /** Past this the LCS table stops being cheap, and such a file yields no diff at all. */
    const val MAX_INPUT_LINES = 2000

    private const val CONTEXT = 3

    /** Null when nothing changed, or when either side is too large to diff cheaply. */
    fun of(old: List<String>, new: List<String>, oldName: String, newName: String): String? {
        if (!fits(old, new)) return null
        val ops = editScript(old, new)
        val ranges = hunkRanges(ops)
        if (ranges.isEmpty()) return null
        val header = listOf("--- a/$oldName", "+++ b/$newName")
        return capped(header + ranges.flatMap { hunk(ops, it) }).joinToString("\n")
    }

    /** Whether both sides are small enough for [of] to diff them. */
    fun fits(old: List<String>, new: List<String>): Boolean = old.size <= MAX_INPUT_LINES && new.size <= MAX_INPUT_LINES

    private fun capped(lines: List<String>): List<String> {
        if (lines.size <= MAX_LINES) return lines
        return lines.take(MAX_LINES) + "... diff truncated at $MAX_LINES lines"
    }

    // ---- everything below is pasted unchanged from CodeArtifacts.kt ----
    // hunk, span, hunkRanges, merge, editScript, walk, stepAt, lcsTable, lengthAt,
    // the private enum Change and the private data class Op.
}
```

Paste the listed private members verbatim (they reference only `CONTEXT`, `Op`, `Change`). Delete the comment line `// ---- everything below …` and the list after pasting.

- [x] **Step 4: Point `CodeArtifacts` at it.** In `adapter/store/CodeArtifacts.kt`:
  - add `import com.brokenfinger.tracker.domain.calc.UnifiedDiff`;
  - replace `diffFromPrev`'s last line and add the warning helper:

```kotlin
    fun diffFromPrev(record: SubmissionRecord, code: String): String? {
        if (!ownsAttemptFile(record)) return null
        val previous = previousInSameLanguage(record) ?: return null
        val before = readAttempt(record, previous) ?: return null
        val after = linesOf(normalized(code))
        if (!UnifiedDiff.fits(before, after)) return tooLarge(before, after)
        return UnifiedDiff.of(before, after, nameOf(record, previous), nameOf(record, record.attempt))
    }

    private fun tooLarge(before: List<String>, after: List<String>): String? {
        logger.warn(
            "Skipped a diff of {} against {} lines — beyond the {} line limit",
            before.size,
            after.size,
            MAX_DIFF_INPUT_LINES,
        )
        return null
    }
```

  - in the companion, replace the two constants with aliases (keep their KDoc):

```kotlin
        /** The diff is inlined into every record line, so a long one is cut off here. */
        const val MAX_DIFF_LINES = UnifiedDiff.MAX_LINES

        /** Past this the LCS table stops being cheap, and such a file yields no diff at all. */
        const val MAX_DIFF_INPUT_LINES = UnifiedDiff.MAX_INPUT_LINES
```

  - delete the `private object UnifiedDiff` block and its KDoc from the file.

- [x] **Step 5:** run `UnifiedDiffTest`, `CodeArtifactsTest`, `FileDerivedArtifactsTest`, `CodeAttachmentTest` → all PASS (the last three unchanged prove the move changed nothing). `./scripts/check.sh` → exit 0.
- [x] **Step 6: Commit** `refactor(domain): move the unified diff into domain/calc` — body: "Repair steps (spec 2026-10-07 §4.3) are computed by a pure calculator, which may not import an adapter. The diff was always a pure function; moved unchanged, its 'too large' warning kept in CodeArtifacts." `Refs #353`.

---

### Task 2: Problem counts per part and level (D11)

**Files:** create `domain/calc/ProblemProgress.kt`, test `ProblemProgressTest.kt`; modify `domain/calc/SubmissionTally.kt`, `SubmissionTallyTest.kt`. Disjoint from Task 1.

- [x] **Step 1: Failing tests** — `src/test/kotlin/com/brokenfinger/tracker/domain/calc/ProblemProgressTest.kt`:

```kotlin
package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aSubmissionRecord
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Zero mocks. Counts of problems, never a judgement about them (spec 2026-10-07 §4.3). */
class ProblemProgressTest {
    @Test
    fun `counts problems, not submits`() {
        val progress = ProblemProgress.of(
            listOf(
                submit(lessonId = 1, at = "10:00", verdict = Verdict.WRONG),
                submit(lessonId = 1, at = "10:05", verdict = Verdict.PASS),
                submit(lessonId = 2, at = "11:00", verdict = Verdict.WRONG),
            ),
        )

        progress.attempted shouldBe 2
        progress.passed shouldBe 1
        progress.passedFirstSubmit shouldBe 0
    }

    /** A run is not an attempt (design §5.1): a problem only ever run was not attempted. */
    @Test
    fun `a problem only ever run was not attempted`() {
        ProblemProgress.of(listOf(run(lessonId = 1, at = "10:00"))).attempted shouldBe 0
    }

    @Test
    fun `a problem whose first submit passed is counted as passing first time`() {
        val progress = ProblemProgress.of(
            listOf(submit(lessonId = 1, at = "10:00", verdict = Verdict.PASS), run(lessonId = 1, at = "09:00")),
        )

        progress.passedFirstSubmit shouldBe 1
    }

    @Test
    fun `runs before the first passing submit count, runs after it do not`() {
        val progress = ProblemProgress.of(
            listOf(
                run(lessonId = 1, at = "09:00"),
                run(lessonId = 1, at = "09:01"),
                submit(lessonId = 1, at = "09:02", verdict = Verdict.PASS),
                run(lessonId = 1, at = "09:03"),
            ),
        )

        progress.runsBeforePass shouldBe 2.0
    }

    @Test
    fun `the median of an even count is the mean of the middle two`() {
        val progress = ProblemProgress.of(
            listOf(run(lessonId = 1, at = "09:00"), submit(lessonId = 1, at = "09:10", verdict = Verdict.PASS)) +
                (0..3).map { run(lessonId = 2, at = "10:0$it") } +
                submit(lessonId = 2, at = "10:10", verdict = Verdict.PASS),
        )

        progress.runsBeforePass shouldBe 2.5
    }

    @Test
    fun `the median of an odd count is the middle one`() {
        val progress = ProblemProgress.of(
            listOf(
                submit(lessonId = 1, at = "09:00", verdict = Verdict.PASS),
                run(lessonId = 2, at = "09:00"),
                submit(lessonId = 2, at = "09:10", verdict = Verdict.PASS),
                run(lessonId = 3, at = "09:00"),
                run(lessonId = 3, at = "09:01"),
                run(lessonId = 3, at = "09:02"),
                submit(lessonId = 3, at = "09:10", verdict = Verdict.PASS),
            ),
        )

        progress.runsBeforePass shouldBe 1.0
    }

    /** Absent is not zero: with no passed problem there is nothing to take a median of. */
    @Test
    fun `no passed problem has no runs-before-pass rather than zero`() {
        ProblemProgress.of(listOf(submit(lessonId = 1, at = "10:00", verdict = Verdict.WRONG)))
            .runsBeforePass.shouldBeNull()
    }

    @Test
    fun `a run in another language before the pass counts too`() {
        val progress = ProblemProgress.of(
            listOf(
                run(lessonId = 1, at = "09:00", language = "kotlin"),
                submit(lessonId = 1, at = "09:10", verdict = Verdict.PASS),
            ),
        )

        progress.runsBeforePass shouldBe 1.0
    }

    private fun submit(lessonId: Long, at: String, verdict: Verdict) = aSubmissionRecord(
        lessonId = lessonId,
        ts = OffsetDateTime.parse("2026-10-01T$at:00+09:00"),
        action = GradingAction.SUBMIT,
        verdict = verdict,
    )

    private fun run(lessonId: Long, at: String, language: String = "java") = aSubmissionRecord(
        lessonId = lessonId,
        ts = OffsetDateTime.parse("2026-10-01T$at:00+09:00"),
        action = GradingAction.RUN,
        attempt = 0,
        language = language,
        verdict = Verdict.WRONG,
    )
}
```

Add to `SubmissionTallyTest` (before the wire-name test), plus imports `io.kotest.matchers.nulls.shouldNotBeNull`:

```kotlin
    @Test
    fun `counts by part, and a part bucket also counts the problems in it`() {
        val records = listOf(
            aSubmissionRecord(lessonId = 1, part = "SELECT", verdict = Verdict.PASS),
            aSubmissionRecord(lessonId = 2, part = "SELECT", verdict = Verdict.WRONG),
        )

        val bucket = SubmissionTally.of(records, TallyGroup.PART).single()

        bucket.key shouldBe "SELECT"
        bucket.count shouldBe 2
        bucket.progress.shouldNotBeNull().attempted shouldBe 2
        bucket.progress.shouldNotBeNull().passed shouldBe 1
    }

    @Test
    fun `counts by level, keyed by the level as text`() {
        val bucket = SubmissionTally.of(listOf(aSubmissionRecord(level = 2)), TallyGroup.LEVEL).single()

        bucket.key shouldBe "2"
        bucket.progress.shouldNotBeNull().attempted shouldBe 1
    }

    /** Absent is not level 0 ([[concepts/assumption-vs-measurement]]). */
    @Test
    fun `a problem with no recorded level or part is a missing key`() {
        SubmissionTally.of(listOf(aSubmissionRecord(level = null)), TallyGroup.LEVEL).single().key.shouldBeNull()
        SubmissionTally.of(listOf(aSubmissionRecord(part = " ")), TallyGroup.PART).single().key.shouldBeNull()
    }

    /** A problem "passed" inside the WRONG bucket would be noise, so verdict buckets carry counts only. */
    @Test
    fun `groupings that are not about problems carry no problem counts`() {
        SubmissionTally.of(listOf(aSubmissionRecord()), TallyGroup.VERDICT).single().progress.shouldBeNull()
    }
```

and change the last test to:

```kotlin
    @Test
    fun `names every group on the wire in lower case`() {
        TallyGroup.wireNames().shouldContainExactly("verdict", "language", "problem", "part", "level")
    }
```

- [x] **Step 2:** run `ProblemProgressTest` and `SubmissionTallyTest` → FAIL (unresolved).
- [x] **Step 3: Implement** `src/main/kotlin/com/brokenfinger/tracker/domain/calc/ProblemProgress.kt`:

```kotlin
package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict

/**
 * How the problems in one `stats` bucket went — counts of problems, never a verdict on them
 * (spec 2026-10-07 §4.3, [[decisions/2026-08-12-the-server-counts-and-names-nothing]]).
 *
 * **Problems, not (problem, language) pairs**: a part or a level is a property of the problem, and
 * `stats(groupBy=problem)` already counts across languages. A run in any language before the first
 * passing submit is a run before the pass.
 *
 * [runsBeforePass] is a median, so a `Double`, and **absent when no problem in the bucket passed** —
 * there is nothing to take a median of, which is not the same as zero runs.
 */
data class ProblemProgress(
    val attempted: Int,
    val passed: Int,
    val passedFirstSubmit: Int,
    val runsBeforePass: Double?,
) {
    companion object {
        /** [records] is one bucket's gradings, runs included; only problems with a submit count. */
        fun of(records: List<SubmissionRecord>): ProblemProgress {
            val problems = records.groupBy { it.lessonId }.values.filter { it.any(SubmissionRecord::isSubmission) }
            return ProblemProgress(
                attempted = problems.size,
                passed = problems.count { firstPass(it) != null },
                passedFirstSubmit = problems.count { firstSubmit(it)?.verdict == Verdict.PASS },
                runsBeforePass = median(problems.mapNotNull(::runsBeforePass)),
            )
        }

        private fun firstSubmit(records: List<SubmissionRecord>): SubmissionRecord? =
            records.filter { it.isSubmission() }.minByOrNull { it.ts }

        private fun firstPass(records: List<SubmissionRecord>): SubmissionRecord? =
            records.filter { it.isSubmission() && it.verdict == Verdict.PASS }.minByOrNull { it.ts }

        private fun runsBeforePass(records: List<SubmissionRecord>): Int? {
            val pass = firstPass(records) ?: return null
            return records.count { !it.isSubmission() && it.ts.isBefore(pass.ts) }
        }

        private fun median(values: List<Int>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            if (sorted.size % 2 == 1) return sorted[middle].toDouble()
            return (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}
```

In `SubmissionTally.kt`:
  - add two constants after `PROBLEM { … },`:

```kotlin
    PART {
        override fun keyOf(record: SubmissionRecord): String? = record.part?.takeIf { it.isNotBlank() }

        override fun countsProblems(): Boolean = true
    },
    LEVEL {
        override fun keyOf(record: SubmissionRecord): String? = record.level?.toString()

        override fun countsProblems(): Boolean = true
    },
```

  - after `open fun labelOf`, add:

```kotlin
    /**
     * Whether a bucket of this grouping also counts its problems ([ProblemProgress]). Only for
     * properties of the problem itself: on a verdict bucket "passed" would mean nothing.
     */
    open fun countsProblems(): Boolean = false
```

  - `TallyBucket` gains a defaulted field (KDoc sentence: "[progress] is present only for groupings that count problems."):

```kotlin
data class TallyBucket(val key: String?, val label: String?, val count: Int, val progress: ProblemProgress? = null)
```

  - replace `SubmissionTally.of` and add two helpers:

```kotlin
    fun of(records: List<SubmissionRecord>, group: TallyGroup): List<TallyBucket> {
        val buckets = records.groupBy(group::keyOf).mapNotNull { (key, grouped) -> bucketOf(group, key, grouped) }
        return ordered(buckets)
    }

    // Grouped with the runs so a problem's progress can count them; `count` stays submits only, and
    // a key only runs ever produced makes no bucket — the rule #235 set.
    private fun bucketOf(group: TallyGroup, key: String?, grouped: List<SubmissionRecord>): TallyBucket? {
        val submits = grouped.filter { it.isSubmission() }
        if (submits.isEmpty()) return null
        return TallyBucket(key, group.labelOf(submits.first()), submits.size, progressOf(group, grouped))
    }

    private fun progressOf(group: TallyGroup, grouped: List<SubmissionRecord>): ProblemProgress? {
        if (!group.countsProblems()) return null
        return ProblemProgress.of(grouped)
    }
```

- [x] **Step 4:** run both classes → PASS; then `./gradlew test --tests 'com.brokenfinger.tracker.adapter.mcp.*'` → PASS (the catalog enum test reads `wireNames()`, so it now lists five groups; the invoker still prints `count` only — Task 7 adds the fields).
- [x] **Step 5: Commit** `feat(domain): count problems per part and level` — `Refs #353`.

---

### Task 3: Code on a timeline, and the steps between gradings (D2–D7)

**Files:** create `domain/calc/CodeTimeline.kt`, `domain/calc/RepairSteps.kt`, `support/fixtures/RepairStepFixtures.kt`, tests `CodeTimelineTest.kt`, `RepairStepsTest.kt`. **Depends on Task 1.**

- [x] **Step 1: Object mothers first** (dev rules §6.4) — `src/test/kotlin/com/brokenfinger/tracker/support/fixtures/RepairStepFixtures.kt`:

```kotlin
package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.adapter.store.RecordLayout
import com.brokenfinger.tracker.domain.GradingAction
import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.SubmissionRecord
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.CodedGrading
import com.brokenfinger.tracker.domain.calc.KeptCode
import java.time.OffsetDateTime

// Object mothers for repair steps (dev rules §6.4, spec 2026-10-07 §4.3). A run's code comes from
// runs.jsonl and a submit's from its attempt file, so the two builders differ in what they own:
// a run carries no codePath here, a submit carries the path the store would have written.

fun aRun(
    at: String,
    verdict: Verdict? = Verdict.WRONG,
    outcome: Outcome = Outcome.JUDGED,
    lessonId: Long = 120804,
    language: String = "java",
): SubmissionRecord = aSubmissionRecord(
    ts = OffsetDateTime.parse(at),
    lessonId = lessonId,
    language = language,
    action = GradingAction.RUN,
    attempt = 0,
    outcome = outcome,
    verdict = verdict,
    score = null,
    rating = null,
    testcases = listOf(aTestcaseResult(passed = verdict == Verdict.PASS, msg = null, runTime = null)),
    codePath = null,
    diffFromPrev = null,
)

fun aSubmit(
    at: String,
    attempt: Int = 1,
    verdict: Verdict? = Verdict.PASS,
    lessonId: Long = 120804,
    language: String = "java",
): SubmissionRecord = aSubmissionRecord(
    ts = OffsetDateTime.parse(at),
    lessonId = lessonId,
    language = language,
    action = GradingAction.SUBMIT,
    attempt = attempt,
    verdict = verdict,
    testcases = listOf(aTestcaseResult(passed = verdict == Verdict.PASS, msg = null, runTime = null)),
    codePath = "problems/$lessonId-두-수의-곱-구하기/attempts/%03d.%s"
        .format(attempt, RecordLayout.extensionOf(language)),
    diffFromPrev = null,
)

fun aKeptCode(text: String = "select 1", fetchedAt: String? = null): KeptCode =
    KeptCode(text, fetchedAt?.let(OffsetDateTime::parse))

fun aCodedGrading(record: SubmissionRecord, code: String? = "select 1", late: Boolean = false): CodedGrading =
    CodedGrading(record, code?.let { aKeptCode(it) }, late)
```

(The directory name `120804-두-수의-곱-구하기` is what `RecordLayout` derives from the fixture's default title; keeping the same directory for submits and runs stops a test from creating two directories for one lesson.)

- [x] **Step 2: Failing tests** — `src/test/kotlin/com/brokenfinger/tracker/domain/calc/CodeTimelineTest.kt`:

```kotlin
package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.support.fixtures.aKeptCode
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aSubmit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Zero mocks. The late-attachment rule of spec 2026-10-07 §4.2: code fetched after the next grading
 * of the problem was recorded may be that grading's code, and the reader must be told.
 */
class CodeTimelineTest {
    @Test
    fun `orders one problem's gradings by time and attaches each one's kept code`() {
        val later = aRun(at = "2026-10-07T10:00:05+09:00")
        val earlier = aRun(at = "2026-10-07T10:00:00+09:00")

        val timeline = CodeTimeline.of(listOf(later, earlier), mapOf(earlier.recordId() to aKeptCode("a")))

        timeline.map { it.record } shouldContainExactly listOf(earlier, later)
        timeline.map { it.code?.text } shouldContainExactly listOf("a", null)
    }

    @Test
    fun `code fetched after the next grading was recorded is late`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:02+09:00")
        val codes = mapOf(first.recordId() to aKeptCode("a", fetchedAt = "2026-10-07T10:00:03+09:00"))

        CodeTimeline.of(listOf(first, second), codes).first().late shouldBe true
    }

    /** Measured 2026-10-07 on lesson 59036: 0.27–0.38 s after the record, well before the next. */
    @Test
    fun `code fetched before the next grading was recorded is not late`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:02+09:00")
        val codes = mapOf(first.recordId() to aKeptCode("a", fetchedAt = "2026-10-07T10:00:00.38+09:00"))

        CodeTimeline.of(listOf(first, second), codes).first().late shouldBe false
    }

    /** The settled rule names the problem's next record, whatever its language. */
    @Test
    fun `the next grading is the problem's next, in any language`() {
        val java = aRun(at = "2026-10-07T10:00:00+09:00", language = "java")
        val kotlin = aRun(at = "2026-10-07T10:00:02+09:00", language = "kotlin")
        val codes = mapOf(java.recordId() to aKeptCode("a", fetchedAt = "2026-10-07T10:00:03+09:00"))

        CodeTimeline.of(listOf(java, kotlin), codes).first().late shouldBe true
    }

    @Test
    fun `the last grading has nothing after it and is never late`() {
        val only = aRun(at = "2026-10-07T10:00:00+09:00")
        val codes = mapOf(only.recordId() to aKeptCode("a", fetchedAt = "2026-10-08T00:00:00+09:00"))

        CodeTimeline.of(listOf(only), codes).single().late shouldBe false
    }

    /** An attempt file records no fetch time, so there is nothing to check (D6). */
    @Test
    fun `code with no fetch time is never late`() {
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val next = aRun(at = "2026-10-07T10:00:02+09:00")

        CodeTimeline.of(listOf(submit, next), mapOf(submit.recordId() to aKeptCode("a"))).first().late shouldBe false
    }

    @Test
    fun `refuses the gradings of two problems`() {
        shouldThrow<IllegalArgumentException> {
            CodeTimeline.of(
                listOf(aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1), aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 2)),
                emptyMap(),
            )
        }
    }
}
```

(Wrap the `listOf(aRun(…), aRun(…))` line if ktlint reports > 120.)

`src/test/kotlin/com/brokenfinger/tracker/domain/calc/RepairStepsTest.kt`:

```kotlin
package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aCodedGrading
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aSubmit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Zero mocks (dev rules §3, §6.1). The cases spec 2026-10-07 §6 names for 4.3 — failed→passed,
 * failed→failed, unknown code on either side, repeated identical runs, run→submit across actions —
 * and the late-attachment case §4.2 added.
 */
class RepairStepsTest {
    @Test
    fun `a failed run followed by a passing run is one step, with the diff between their code`() {
        val failed = aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "select a\nfrom t")
        val passed = aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "select b\nfrom t")

        val step = RepairSteps.of(listOf(failed, passed)).single()

        step.from shouldBe failed
        step.to shouldBe passed
        step.diff shouldBe "--- a/from\n+++ b/to\n@@ -1,2 +1,2 @@\n-select a\n+select b\n from t"
        step.noDiff.shouldBeNull()
    }

    @Test
    fun `each failure followed by a change is its own step`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = "b"),
            aCodedGrading(aRun(at = T2, verdict = Verdict.WRONG), code = "c"),
        )

        RepairSteps.of(timeline).map { it.to.code?.text } shouldContainExactly listOf("b", "c")
    }

    @Test
    fun `a passing grading starts no step`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.PASS), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = "b"),
        )

        RepairSteps.of(timeline).shouldBeEmpty()
    }

    /** D3: 25 of 65 measured candidate steps start at a grading no verdict was resolved for. */
    @Test
    fun `an unresolved grading starts a step, because unresolved is not passed`() {
        val unresolved = aCodedGrading(aRun(at = T0, verdict = null, outcome = Outcome.UNKNOWN), code = "a")
        val next = aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "b")

        RepairSteps.of(listOf(unresolved, next)).single().from shouldBe unresolved
    }

    @Test
    fun `repeated identical runs make no step — nothing was corrected`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aRun(at = T2, verdict = Verdict.PASS), code = "b"),
        )

        val step = RepairSteps.of(timeline).single()

        step.from shouldBe timeline[1]
    }

    /** runs.jsonl keeps code as fetched; attempts/ keeps it with one trailing newline (D4). */
    @Test
    fun `code that differs only by trailing newlines is identical`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aSubmit(at = T1, verdict = Verdict.WRONG), code = "a\n"),
        )

        RepairSteps.of(timeline).shouldBeEmpty()
    }

    @Test
    fun `a step whose earlier code is unknown has no diff and says so`() {
        val step = RepairSteps.of(
            listOf(
                aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = null),
                aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "b"),
            ),
        ).single()

        step.diff.shouldBeNull()
        step.noDiff shouldBe NoDiff.FROM_CODE_UNKNOWN
    }

    @Test
    fun `a step whose later code is unknown has no diff and says so`() {
        val step = RepairSteps.of(
            listOf(
                aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
                aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = null),
            ),
        ).single()

        step.noDiff shouldBe NoDiff.TO_CODE_UNKNOWN
    }

    /** Spec §4.3: "never dropped and never paired across the gap". */
    @Test
    fun `a gap in the code is never bridged`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = null),
            aCodedGrading(aRun(at = T2, verdict = Verdict.PASS), code = "b"),
        )

        RepairSteps.of(timeline).map { it.noDiff } shouldContainExactly
            listOf(NoDiff.TO_CODE_UNKNOWN, NoDiff.FROM_CODE_UNKNOWN)
    }

    @Test
    fun `both sides unknown is one reason, not two`() {
        val step = RepairSteps.of(
            listOf(
                aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = null),
                aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = null),
            ),
        ).single()

        step.noDiff shouldBe NoDiff.CODE_UNKNOWN
    }

    @Test
    fun `a failed run followed by a submit is a step across actions`() {
        val run = aCodedGrading(aRun(at = T0, verdict = Verdict.COMPILE_ERROR), code = "a")
        val submit = aCodedGrading(aSubmit(at = T1, verdict = Verdict.PASS), code = "b")

        RepairSteps.of(listOf(run, submit)).single().to shouldBe submit
    }

    @Test
    fun `each language is paired with itself`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG, language = "java"), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.PASS, language = "kotlin"), code = "k"),
            aCodedGrading(aRun(at = T2, verdict = Verdict.PASS, language = "java"), code = "b"),
        )

        val step = RepairSteps.of(timeline).single()

        step.from shouldBe timeline[0]
        step.to shouldBe timeline[2]
    }

    /** D4: a late side may hold the later grading's code, so "identical" proves nothing there. */
    @Test
    fun `identical code beside a late side is still a step`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "b", late = true),
            aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "b"),
        )

        RepairSteps.of(timeline).single().noDiff shouldBe NoDiff.SAME_CODE
    }

    @Test
    fun `code too large to diff says so`() {
        val huge = (0..UnifiedDiff.MAX_INPUT_LINES).joinToString("\n") { "x$it" }
        val step = RepairSteps.of(
            listOf(
                aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = huge),
                aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "b"),
            ),
        ).single()

        step.noDiff shouldBe NoDiff.TOO_LARGE
    }

    /** `get_problem(include=runs)` reads every transition, passing starts and unchanged code included. */
    @Test
    fun `transitions keep what steps leave out`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.PASS), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "a"),
        )

        RepairSteps.transitions(timeline).single().noDiff shouldBe NoDiff.SAME_CODE
    }

    @Test
    fun `no-diff reasons have wire names`() {
        NoDiff.entries.map { it.wireName() } shouldContainExactly
            listOf("fromCodeUnknown", "toCodeUnknown", "codeUnknown", "sameCode", "tooLarge")
    }

    private companion object {
        const val T0 = "2026-10-07T10:00:00+09:00"
        const val T1 = "2026-10-07T10:00:05+09:00"
        const val T2 = "2026-10-07T10:00:10+09:00"
    }
}
```

- [x] **Step 3:** run both classes → FAIL (unresolved types).
- [x] **Step 4: Implement** `src/main/kotlin/com/brokenfinger/tracker/domain/calc/CodeTimeline.kt`:

```kotlin
package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.SubmissionRecord
import java.time.OffsetDateTime

/**
 * A grading's code as it was kept. [fetchedAt] is when it was attached — known for a run
 * (`runs.jsonl`'s `codeFetchedAt`), absent for a submit, whose attempt file records no such time.
 */
data class KeptCode(val text: String, val fetchedAt: OffsetDateTime?)

/**
 * One grading as repair steps see it: the record, its code when kept, and whether that code was
 * attached after the problem's next grading had been recorded — in which case it may be that
 * grading's code (spec 2026-10-07 §4.2).
 */
data class CodedGrading(val record: SubmissionRecord, val code: KeptCode?, val late: Boolean)

/**
 * One problem's gradings in time order, each with its kept code (dev rules §3 — no I/O).
 *
 * **Late is decided against the problem's next grading in any language**, which is why this takes
 * the whole problem rather than one language: the rule compares the fetch with the next record of
 * the problem, and a timeline cut to one language cannot see it. It catches a late attachment —
 * the startup retry after an expired session — and can miss a second Run pressed within the
 * ~0.3 s the fetch takes ([[decisions/2026-10-07-every-run-keeps-its-code]]).
 *
 * The sort is stable, so gradings that share a timestamp keep the order they were handed in —
 * the caller passes them oldest first, in log order.
 */
object CodeTimeline {
    fun of(records: List<SubmissionRecord>, codes: Map<String, KeptCode>): List<CodedGrading> {
        require(records.map { it.lessonId }.distinct().size <= 1) { "a code timeline is one problem's" }
        val ordered = records.sortedBy { it.ts }
        return ordered.mapIndexed { index, record ->
            coded(record, codes[record.recordId()], ordered.getOrNull(index + 1))
        }
    }

    private fun coded(record: SubmissionRecord, code: KeptCode?, next: SubmissionRecord?): CodedGrading =
        CodedGrading(record, code, isLate(code, next))

    private fun isLate(code: KeptCode?, next: SubmissionRecord?): Boolean {
        val fetchedAt = code?.fetchedAt ?: return false
        if (next == null) return false
        return fetchedAt.isAfter(next.ts)
    }
}
```

`src/main/kotlin/com/brokenfinger/tracker/domain/calc/RepairSteps.kt`:

```kotlin
package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Verdict

/** Why a transition carries no diff. Exactly one of a diff and a reason is present. */
enum class NoDiff(private val wire: String) {
    /** The earlier grading's code was not kept — a run recorded before 2026-10-07, or code never attached. */
    FROM_CODE_UNKNOWN("fromCodeUnknown"),
    TO_CODE_UNKNOWN("toCodeUnknown"),
    CODE_UNKNOWN("codeUnknown"),

    /** Both sides hold the same code. A repair step shows this only beside a late side. */
    SAME_CODE("sameCode"),

    /** Over [UnifiedDiff.MAX_INPUT_LINES] lines on a side. */
    TOO_LARGE("tooLarge"),
    ;

    fun wireName(): String = wire

    companion object {
        fun unknown(fromMissing: Boolean, toMissing: Boolean): NoDiff {
            if (fromMissing && toMissing) return CODE_UNKNOWN
            if (fromMissing) return FROM_CODE_UNKNOWN
            return TO_CODE_UNKNOWN
        }
    }
}

/** One grading and the next grading of the same problem in the same language, with what changed. */
data class Transition(val from: CodedGrading, val to: CodedGrading, val diff: String?, val noDiff: NoDiff?) {
    /**
     * A repair step: the earlier grading did not pass — an unresolved verdict included, since
     * unresolved is not passed — and something may have changed. Identical code makes no step,
     * **unless a side's code was attached late**: then "identical" may only mean both sides hold
     * the later grading's code, and dropping the step would hide exactly that.
     */
    fun isRepairStep(): Boolean = from.record.verdict != Verdict.PASS && !unchanged()

    private fun unchanged(): Boolean = noDiff == NoDiff.SAME_CODE && !from.late && !to.late
}

/**
 * The corrections between gradings (spec 2026-10-07 §4.3) — a pure calculator (dev rules §3).
 *
 * It pairs each grading with the **next grading of the same problem in the same language**, run
 * or submit, and diffs their code. It never pairs across a grading whose code is unknown: that
 * pair is returned without a diff and says why. What a change *means* — a habit, a slip, a
 * misread relation — is not decided here or anywhere in the server
 * ([[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]]).
 */
object RepairSteps {
    private const val FROM = "from"
    private const val TO = "to"

    /** The steps a reader studies: failures followed by a change, or by code that cannot be trusted. */
    fun of(timeline: List<CodedGrading>): List<Transition> = transitions(timeline).filter(Transition::isRepairStep)

    /** Every consecutive pair per language, of the timeline [CodeTimeline.of] built. */
    fun transitions(timeline: List<CodedGrading>): List<Transition> = timeline
        .groupBy { it.record.language.lowercase() }
        .values
        .flatMap { it.zipWithNext(::between) }

    private fun between(from: CodedGrading, to: CodedGrading): Transition {
        val old = from.code?.text
        val new = to.code?.text
        if (old == null || new == null) return Transition(from, to, null, NoDiff.unknown(old == null, new == null))
        return compared(from, to, linesOf(old), linesOf(new))
    }

    private fun compared(from: CodedGrading, to: CodedGrading, old: List<String>, new: List<String>): Transition {
        if (old == new) return Transition(from, to, null, NoDiff.SAME_CODE)
        val diff = UnifiedDiff.of(old, new, FROM, TO) ?: return Transition(from, to, null, NoDiff.TOO_LARGE)
        return Transition(from, to, diff, null)
    }

    // runs.jsonl keeps code as fetched; an attempt file has exactly one trailing newline. Compared
    // raw, the same code read from the two places would differ.
    private fun linesOf(text: String): List<String> {
        val body = text.trimEnd('\n')
        if (body.isEmpty()) return emptyList()
        return body.split("\n")
    }
}
```

- [x] **Step 5:** run both classes → PASS. Then break `unchanged()` (drop `&& !from.late && !to.late`) and confirm `identical code beside a late side is still a step` fails; restore. Break `isLate` (`return false` first) and confirm `code fetched after the next grading was recorded is late` fails; restore.
- [x] **Step 6:** `./gradlew test verifyBranchCoverage` → `domain/calc` ≥ 95%. If below, the report names the uncovered branches; add a test, never an exemption.
- [x] **Step 7: Commit** `feat(domain): pair gradings into repair steps` — body cites spec §4.3 and the measured 65 candidate steps. `Refs #353`.

---

### Task 4: Filtering and labelling steps (D8, D14)

**Files:** create `domain/calc/RepairStepFilter.kt`, test `RepairStepFilterTest.kt`. **Depends on Task 3.** Disjoint from Task 5.

- [x] **Step 1: Failing test** — `src/test/kotlin/com/brokenfinger/tracker/domain/calc/RepairStepFilterTest.kt`:

```kotlin
package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.support.fixtures.aCodedGrading
import com.brokenfinger.tracker.support.fixtures.aRun
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RepairStepFilterTest {
    private val select = ProblemLabel(lessonId = 1, title = "a", level = 1, part = "SELECT")
    private val join = ProblemLabel(lessonId = 2, title = "b", level = 2, part = "JOIN")
    private val unlabelled = ProblemLabel(lessonId = 3, title = null, level = null, part = null)

    /** D8: the correction is the event, so the order is by when it was recorded. */
    @Test
    fun `answers newest first, by when the correction was recorded`() {
        val older = stepOf(select, toAt = "2026-10-02T10:00:00+09:00")
        val newer = stepOf(join, toAt = "2026-10-03T10:00:00+09:00")

        filter().applied(listOf(older, newer)) shouldContainExactly listOf(newer, older)
    }

    @Test
    fun `since bounds the correction's time`() {
        val before = stepOf(select, toAt = "2026-09-30T23:00:00+09:00")
        val after = stepOf(select, toAt = "2026-10-01T09:00:00+09:00")

        filter(since = Since.Day(LocalDate.of(2026, 10, 1))).applied(listOf(before, after)) shouldContainExactly
            listOf(after)
    }

    @Test
    fun `language matches case-insensitively and in full`() {
        val java = stepOf(select, toAt = "2026-10-02T10:00:00+09:00", language = "java")
        val javascript = stepOf(select, toAt = "2026-10-02T11:00:00+09:00", language = "javascript")

        filter(language = " JAVA ").applied(listOf(java, javascript)) shouldContainExactly listOf(java)
    }

    @Test
    fun `part matches case-insensitively, and a step with no recorded part is left out`() {
        val selected = stepOf(select, toAt = "2026-10-02T10:00:00+09:00")
        val none = stepOf(unlabelled, toAt = "2026-10-02T11:00:00+09:00")

        filter(part = "select").applied(listOf(selected, none, stepOf(join, "2026-10-02T12:00:00+09:00"))) shouldContainExactly
            listOf(selected)
    }

    @Test
    fun `limit keeps the newest`() {
        val steps = (1..3).map { stepOf(select, toAt = "2026-10-0${it}T10:00:00+09:00") }

        filter(limit = 2).applied(steps) shouldContainExactly listOf(steps[2], steps[1])
    }

    @Test
    fun `no argument is the whole list`() {
        val steps = listOf(stepOf(unlabelled, toAt = "2026-10-02T10:00:00+09:00"))

        filter().applied(steps) shouldContainExactly steps
    }

    private fun filter(since: Since? = null, language: String? = null, part: String? = null, limit: Int? = null) =
        RepairStepFilter(since, language, part, limit)

    private fun stepOf(label: ProblemLabel, toAt: String, language: String = "java"): LabelledStep {
        val from = aCodedGrading(aRun(at = "2026-09-01T00:00:00+09:00", lessonId = label.lessonId, language = language))
        val to = aCodedGrading(aRun(at = toAt, lessonId = label.lessonId, language = language))
        return LabelledStep(label, Transition(from, to, diff = "d", noDiff = null))
    }
}
```

(Wrap the long `part` assertion line for ktlint.)

- [x] **Step 2:** run → FAIL.
- [x] **Step 3: Implement** `src/main/kotlin/com/brokenfinger/tracker/domain/calc/RepairStepFilter.kt`:

```kotlin
package com.brokenfinger.tracker.domain.calc

/**
 * What a step is about, for a reader who sees it apart from its problem. Each field is the newest
 * record's that carries one, as `get_problem` resolves them; absent when none was recorded.
 */
data class ProblemLabel(val lessonId: Long, val title: String?, val level: Int?, val part: String?)

data class LabelledStep(val problem: ProblemLabel, val step: Transition)

/**
 * Narrows and orders repair steps (dev rules §3). Every argument is optional, and an absent one is
 * not a filter.
 *
 * Applied **after** pairing, so the first step after [since] still starts at the failure that
 * preceded it. [since] bounds the correction — the `to` side — and the answer is newest first by
 * it, because the correction is the event the question is about.
 */
data class RepairStepFilter(val since: Since?, val language: String?, val part: String?, val limit: Int?) {
    fun applied(steps: List<LabelledStep>): List<LabelledStep> {
        val kept = steps.filter(::admits).sortedByDescending { it.step.to.record.ts }
        return limit?.let(kept::take) ?: kept
    }

    private fun admits(step: LabelledStep): Boolean =
        admitsTime(step) && matches(language, step.step.to.record.language) && matches(part, step.problem.part)

    private fun admitsTime(step: LabelledStep): Boolean = since == null || since.includes(step.step.to.record.ts)

    private fun matches(wanted: String?, actual: String?): Boolean =
        wanted == null || wanted.trim().equals(actual, ignoreCase = true)
}
```

- [x] **Step 4:** run → PASS.
- [x] **Step 5: Commit** `feat(domain): filter repair steps by time, language and part` — `Refs #353`.

---

### Task 5: The kept-code port and its store adapter (D15, D16)

**Files:** create `application/GradingCodes.kt`, `adapter/store/FileGradingCodes.kt`, test `adapter/store/FileGradingCodesTest.kt`; modify `adapter/store/RunLog.kt`, `adapter/store/RecordLayout.kt`, `RecordLayoutTest.kt`. **Depends on Task 3** (`KeptCode`). Disjoint from Task 4.

- [x] **Step 1: Failing tests.** Append to `RecordLayoutTest` (imports `io.kotest.matchers.nulls.shouldBeNull`):

```kotlin
    @Test
    fun `a path a record carries resolves inside the repository`() {
        RecordLayout(root).recordFile("problems/1-a/attempts/001.java") shouldBe
            root.toAbsolutePath().normalize().resolve("problems/1-a/attempts/001.java")
    }

    /** D15: the MCP read path must not follow a log line out of the repository. */
    @Test
    fun `a path that climbs out of the repository resolves to nothing`() {
        RecordLayout(root).recordFile("../escape.txt").shouldBeNull()
        RecordLayout(root).recordFile("problems/../../escape.txt").shouldBeNull()
        RecordLayout(root).recordFile(root.parent.resolve("x").toString()).shouldBeNull()
    }

    @Test
    fun `the repository itself and an unusable path are not files of it`() {
        RecordLayout(root).recordFile("").shouldBeNull()
        RecordLayout(root).recordFile("a\u0000b").shouldBeNull()
    }
```

`src/test/kotlin/com/brokenfinger/tracker/adapter/store/FileGradingCodesTest.kt`:

```kotlin
package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.domain.calc.KeptCode
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aSubmit
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Layer test over a real directory (dev rules §6.1): what [RunLog] and the attempt writer leave on
 * disk is read back by the read side, which holds no writer of its own.
 */
class FileGradingCodesTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `reads each run's code back by its record id, with the time it was attached`() {
        val run = aRun(at = "2026-10-07T10:51:49+09:00")
        writeRun(run, "select 1", attachedAt = "2026-10-07T10:51:49.38+09:00")

        codes().runs(run.lessonId, run.title)[run.recordId()] shouldBe
            KeptCode("select 1", OffsetDateTime.parse("2026-10-07T10:51:49.38+09:00"))
    }

    @Test
    fun `a problem with no run log keeps nothing`() {
        codes().runs(120804, "두 수의 곱 구하기").shouldBeEmpty()
    }

    /** The crash the writer heals must not cost the reader the lines before it. */
    @Test
    fun `a torn last line is left out and the lines before it survive`() {
        val run = aRun(at = "2026-10-07T10:51:49+09:00")
        writeRun(run, "select 1", attachedAt = "2026-10-07T10:51:49.38+09:00")
        Files.writeString(runLog(), "{\"recordId\":\"torn", StandardOpenOption.APPEND)

        codes().runs(run.lessonId, run.title).keys shouldBe setOf(run.recordId())
    }

    @Test
    fun `an unreadable attach time leaves the code with no time`() {
        Files.createDirectories(runLog().parent)
        Files.writeString(runLog(), """{"recordId":"r#1","language":"java","codeFetchedAt":"later","code":"x"}""" + "\n")

        codes().runs(120804, "두 수의 곱 구하기")["r#1"] shouldBe KeptCode("x", null)
    }

    @Test
    fun `reads a submit's code from the path its record carries`() {
        val submit = aSubmit(at = "2026-10-07T11:00:00+09:00")
        val file = root.resolve(submit.codePath!!)
        Files.createDirectories(file.parent)
        Files.writeString(file, "select 2\n")

        codes().submitted(submit.codePath!!) shouldBe "select 2\n"
    }

    @Test
    fun `a submit whose file is gone has no code`() {
        codes().submitted("problems/120804-x/attempts/009.java").shouldBeNull()
    }

    @Test
    fun `a path that leaves the repository is not followed`() {
        codes().submitted("../../etc/hosts").shouldBeNull()
    }

    @Test
    fun `a directory where a file should be is no code, not an error`() {
        Files.createDirectories(root.resolve("problems/1/attempts/001.java"))

        codes().submitted("problems/1/attempts/001.java").shouldBeNull()
    }

    private fun writeRun(run: com.brokenfinger.tracker.domain.SubmissionRecord, code: String, attachedAt: String) {
        val at = OffsetDateTime.parse(attachedAt)
        RunLog(RecordLayout(root), Clock.fixed(at.toInstant(), at.offset)).append(run, code)
    }

    private fun runLog(): Path = RecordLayout(root).runLog(120804, "두 수의 곱 구하기")

    private fun codes() = FileGradingCodes(RecordLayout(root))
}
```

- [x] **Step 2:** run `RecordLayoutTest`, `FileGradingCodesTest` → FAIL.
- [x] **Step 3: Implement.**

`src/main/kotlin/com/brokenfinger/tracker/application/GradingCodes.kt`:

```kotlin
package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.calc.KeptCode

/**
 * Reads back the code kept beside the records — each run's line in `runs.jsonl`, each submit's
 * attempt file — for repair steps (spec 2026-10-07 §4.3).
 *
 * An outbound port for the reason [ProblemStatements] is one: the code is files, this layer knows
 * no filesystem, and the read side must hold nothing that can write
 * ([[decisions/2026-08-06-mcp-read-slice]]). Never throws: code that cannot be read is code that
 * was not kept, and the step it belonged to says so.
 */
interface GradingCodes {
    /** Every kept run of one problem, by `SubmissionRecord.recordId()`. Empty when none was kept. */
    fun runs(lessonId: Long, title: String?): Map<String, KeptCode>

    /** A submit's code, from the path its record carries. Null when gone, unreadable or outside the repository. */
    fun submitted(codePath: String): String?

    companion object {
        /** Keeps nothing — what a repository that never kept code answers. */
        val NONE: GradingCodes = object : GradingCodes {
            override fun runs(lessonId: Long, title: String?): Map<String, KeptCode> = emptyMap()

            override fun submitted(codePath: String): String? = null
        }
    }
}
```

`RecordLayout.kt` — after `runLog`:

```kotlin
    /**
     * A path a record carries (`codePath`), resolved inside the repository — or null when it would
     * leave it, or names nothing usable. The log is ours, but the MCP read path must not follow
     * `../` out of the repository on the strength of a line someone could have edited.
     */
    fun recordFile(relative: String): Path? {
        val base = root.toAbsolutePath().normalize()
        val file = runCatching { base.resolve(relative).normalize() }.getOrNull() ?: return null
        return file.takeIf { it.startsWith(base) && it != base }
    }
```

`RunLog.kt` — replace the private nested `Line` with a top-level internal type (update its one use to `RunLine(...)`):

```kotlin
/**
 * One line of `runs.jsonl` — exactly these four keys (spec 2026-10-07 §4.2). Internal so the read
 * side, [FileGradingCodes], decodes the shape this file writes rather than a copy of it.
 */
@Serializable
internal data class RunLine(val recordId: String, val language: String, val codeFetchedAt: String, val code: String)
```

`src/main/kotlin/com/brokenfinger/tracker/adapter/store/FileGradingCodes.kt`:

```kotlin
package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.application.GradingCodes
import com.brokenfinger.tracker.domain.calc.KeptCode
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * The code kept beside the records, read back for repair steps (spec 2026-10-07 §4.3).
 *
 * Lenient in the posture of every reader here (dev rules §4): a torn line, an unreadable file, a
 * time that does not parse or a path that leaves the repository answers "not kept" for that one
 * grading and never fails the answer. It holds no [RunLog] — the read side cannot append.
 *
 * A run appears once per `recordId`; should a line ever repeat, the first wins, as the writer's
 * idempotency check treats the first complete line as the record.
 */
class FileGradingCodes(private val layout: RecordLayout) : GradingCodes {
    override fun runs(lessonId: Long, title: String?): Map<String, KeptCode> =
        runCatching { keptIn(layout.runLog(lessonId, title)) }.getOrDefault(emptyMap())

    override fun submitted(codePath: String): String? {
        val file = layout.recordFile(codePath) ?: return null
        return runCatching { String(Files.readAllBytes(file), CHARSET) }.getOrNull()
    }

    private fun keptIn(file: Path): Map<String, KeptCode> {
        if (!Files.isRegularFile(file)) return emptyMap()
        return String(Files.readAllBytes(file), CHARSET).split('\n')
            .mapNotNull(::decoded)
            .distinctBy { it.recordId }
            .associate { it.recordId to KeptCode(it.code, fetchedAtOf(it.codeFetchedAt)) }
    }

    private fun decoded(line: String): RunLine? =
        runCatching { format.decodeFromString(RunLine.serializer(), line) }.getOrNull()

    // An unreadable time is an unknown one: the code is still the code, it just cannot be checked.
    private fun fetchedAtOf(text: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(text) }.getOrNull()

    private companion object {
        val CHARSET = StandardCharsets.UTF_8
        val format = Json { ignoreUnknownKeys = true }
    }
}
```

- [x] **Step 4:** run `RecordLayoutTest`, `FileGradingCodesTest`, `RunLogTest`, `FileDerivedArtifactsTest` → PASS.
- [x] **Step 5: Commit** `feat(store): read kept run and submit code back for repair steps` — `Refs #353`.

---

### Task 6: `RecordQuery` assembles steps and coded problems; wiring

**Files:** modify `application/RecordQuery.kt`, `RecordQueryTest.kt`, `support/fixtures/RecordRepositoryFixtures.kt`, `adapter/config/McpConfiguration.kt`. **Depends on Tasks 4 and 5.**

- [x] **Step 1: Fixture.** In `RecordRepositoryFixture` (imports `RunLog`, `FileGradingCodes`, `java.time.OffsetDateTime`):

```kotlin
    /** A run's code, kept the way the capture path keeps it — through the real [RunLog]. */
    fun withRunCode(
        record: SubmissionRecord,
        code: String,
        attachedAt: OffsetDateTime = record.ts.plusNanos(MEASURED_FETCH_NANOS),
    ): RecordRepositoryFixture = apply {
        RunLog(RecordLayout(root), Clock.fixed(attachedAt.toInstant(), attachedAt.offset)).append(record, code)
    }

    /** A submit's attempt file, at the path its record carries. */
    fun withSubmitCode(record: SubmissionRecord, code: String): RecordRepositoryFixture = apply {
        val file = root.resolve(requireNotNull(record.codePath) { "a submit with code carries its path" })
        Files.createDirectories(file.parent)
        Files.writeString(file, code)
    }
```

with `private companion object { const val MEASURED_FETCH_NANOS = 300_000_000L }` (0.24–0.53 s measured on 59035, 0.27–0.38 s on 59036), and `query()` now reads kept code through the real adapter:

```kotlin
    ): RecordQuery = RecordQuery(store(), catalog, clock, raw, codes = FileGradingCodes(RecordLayout(root)))
```

- [x] **Step 2: Failing tests** — append to `RecordQueryTest` (imports: `aRun`, `aSubmit`, `FileRawSessionLog`, `anEmptyCatalog`, `NoDiff`, `shouldNotBeNull`, `shouldContain`, `java.time.Clock`):

```kotlin
    /** Spec §6, 4.3 acceptance in miniature: a failure, the run that corrected it, and the change. */
    @Test
    fun `a failed run and the run that followed it are a repair step with the diff of their kept code`() {
        val failed = aRun(at = "2026-10-07T10:51:49+09:00")
        val passed = aRun(at = "2026-10-07T10:51:51+09:00", verdict = Verdict.PASS)
        val query = aRecordRepository(root).containing(failed, passed)
            .withRunCode(failed, "select a").withRunCode(passed, "select b").query()

        val labelled = query.repairSteps(since = null, language = null, part = null, lessonId = null, limit = null)
            .single()

        labelled.step.from.record.recordId() shouldBe failed.recordId()
        labelled.step.diff.shouldNotBeNull() shouldContain "+select b"
        labelled.problem.title shouldBe "두 수의 곱 구하기"
    }

    @Test
    fun `a run recorded before code was kept is a step that says its code is unknown`() {
        val failed = aRun(at = "2026-10-06T10:00:00+09:00")
        val passed = aRun(at = "2026-10-07T10:00:00+09:00", verdict = Verdict.PASS)
        val query = aRecordRepository(root).containing(failed, passed).withRunCode(passed, "b").query()

        query.repairSteps(null, null, null, null, null).single().step.noDiff shouldBe NoDiff.FROM_CODE_UNKNOWN
    }

    @Test
    fun `a submit's code is read from its attempt file`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:01:00+09:00")
        val query = aRecordRepository(root).containing(run, submit)
            .withSubmitCode(submit, "fixed\n").withRunCode(run, "broken").query()

        query.repairSteps(null, null, null, null, null).single().step.diff.shouldNotBeNull() shouldContain "+fixed"
    }

    @Test
    fun `code attached after the next grading was recorded is marked late`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:02+09:00", verdict = Verdict.PASS)
        val query = aRecordRepository(root).containing(first, second)
            .withRunCode(first, "b", attachedAt = second.ts.plusSeconds(1))
            .withRunCode(second, "b").query()

        query.repairSteps(null, null, null, null, null).single().step.from.late shouldBe true
    }

    @Test
    fun `narrows to one lesson before reading any code`() {
        val query = aRecordRepository(root).containing(
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 2),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 2),
        ).query()

        query.repairSteps(null, null, null, lessonId = 2, limit = null).map { it.problem.lessonId }
            .shouldContainExactly(2L)
    }

    @Test
    fun `one problem's coded timeline carries each grading's code and the transition into it`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:05+09:00", verdict = Verdict.PASS)
        val coded = aRecordRepository(root).containing(first, second)
            .withRunCode(first, "a").withRunCode(second, "b").query()
            .codedProblem(120804)

        coded.codeOf(first)?.code?.text shouldBe "a"
        coded.transitionInto(second).shouldNotBeNull().diff.shouldNotBeNull() shouldContain "+b"
        coded.transitionInto(first).shouldBeNull()
    }

    /** The default port: a deployment without kept code still answers, with every code unknown. */
    @Test
    fun `a query with no code store answers every step without a diff`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:01:00+09:00")
        val store = aRecordRepository(root).containing(run, submit).store()
        val query = RecordQuery(store, anEmptyCatalog(), Clock.systemUTC(), FileRawSessionLog.under(root))

        query.repairSteps(null, null, null, null, null).single().step.noDiff shouldBe NoDiff.CODE_UNKNOWN
    }
```

- [x] **Step 3:** run `RecordQueryTest` → the new tests FAIL (unresolved).
- [x] **Step 4: Implement** in `RecordQuery.kt`:

Below `ProblemHistory`, add the result DTO:

```kotlin
/**
 * One problem's gradings with their kept code, and each one's transition from the grading before
 * it in its language — what `get_problem(include=…)` adds (spec 2026-10-07 §4.3).
 */
data class CodedProblem(val gradings: List<CodedGrading>, val transitions: List<Transition>) {
    fun codeOf(record: SubmissionRecord): CodedGrading? =
        gradings.firstOrNull { it.record.recordId() == record.recordId() }

    /** Null for the first grading in its language — there is nothing before it to compare with. */
    fun transitionInto(record: SubmissionRecord): Transition? =
        transitions.firstOrNull { it.to.record.recordId() == record.recordId() }
}
```

Constructor gains the port last, defaulted so `McpControllerTest` and the fixture keep compiling:

```kotlin
    private val statements: ProblemStatements = ProblemStatements { _, _ -> null },
    private val codes: GradingCodes = GradingCodes.NONE,
```

Methods (after `problem`):

```kotlin
    /**
     * Every correction after a failed grading, newest first (spec 2026-10-07 §4.3).
     *
     * Paired over each problem's **whole** history before any filter applies, so the first step
     * after [since] still starts at the failure before it. [lessonId] narrows first, so asking
     * about one problem reads one problem's code.
     */
    fun repairSteps(since: Since?, language: String?, part: String?, lessonId: Long?, limit: Int?): List<LabelledStep> {
        val problems = history().groupBy { it.lessonId }.filterKeys { lessonId == null || it == lessonId }
        val steps = problems.values.flatMap(::labelledSteps)
        return RepairStepFilter(since, language, part, limit).applied(steps)
    }

    /** One problem's gradings with their code and transitions, for `get_problem(include=…)`. */
    fun codedProblem(lessonId: Long): CodedProblem {
        val timeline = timelineOf(SubmissionFilter.ofProblem(history(), lessonId))
        return CodedProblem(timeline, RepairSteps.transitions(timeline))
    }

    private fun labelledSteps(newestFirst: List<SubmissionRecord>): List<LabelledStep> {
        val label = labelOf(newestFirst)
        return RepairSteps.of(timelineOf(newestFirst)).map { LabelledStep(label, it) }
    }

    // history() is newest first with ties in reverse log order; reversed, ties are in log order,
    // which is what the timeline's stable sort keeps.
    private fun timelineOf(newestFirst: List<SubmissionRecord>): List<CodedGrading> {
        val oldestFirst = newestFirst.asReversed()
        return CodeTimeline.of(oldestFirst, codesOf(oldestFirst))
    }

    private fun codesOf(records: List<SubmissionRecord>): Map<String, KeptCode> {
        val any = records.firstOrNull() ?: return emptyMap()
        return codes.runs(any.lessonId, any.title) + records.mapNotNull(::submittedCode).toMap()
    }

    private fun submittedCode(record: SubmissionRecord): Pair<String, KeptCode>? {
        if (!record.isSubmission() || !record.isCodeAttached()) return null
        val text = record.codePath?.let(codes::submitted) ?: return null
        return record.recordId() to KeptCode(text, fetchedAt = null)
    }

    private fun labelOf(newestFirst: List<SubmissionRecord>): ProblemLabel = ProblemLabel(
        lessonId = newestFirst.first().lessonId,
        title = newest(newestFirst) { it.title.takeIf(String::isNotBlank) },
        level = newest(newestFirst) { it.level },
        part = newest(newestFirst) { it.part?.takeIf(String::isNotBlank) },
    )
```

Imports: `domain.calc.CodeTimeline`, `CodedGrading`, `KeptCode`, `LabelledStep`, `ProblemLabel`, `RepairStepFilter`, `RepairSteps`, `Transition`. The `repairSteps` signature line is > 120 characters — wrap its parameters one per line with a trailing comma.

`McpConfiguration.recordQuery`:

```kotlin
    @Bean
    fun recordQuery(layout: RecordLayout, catalog: ProblemCatalog, clock: Clock, raw: RawSessionLog): RecordQuery =
        RecordQuery(
            store = JsonlRecordStore(layout.submissionLog()),
            catalog = catalog,
            clock = clock,
            raw = raw,
            statements = FileProblemStatements(layout),
            codes = FileGradingCodes(layout),
        )
```

- [x] **Step 5:** `./scripts/test.sh` → PASS (the context-load test proves the wiring). Break `timelineOf` (drop `asReversed()`) and confirm nothing silently passes that should not — if no test fails, add one with two gradings sharing a `ts` (a run and the submit after it in the same second) asserting the run is `from`; restore.
- [x] **Step 6: Commit** `feat(application): assemble repair steps and coded problems` — `Refs #353`.

---

### Task 7: `stats` by part and level over MCP

**Files:** `adapter/mcp/McpToolInvoker.kt`, `McpToolCatalog.kt`, `McpToolInvokerTest.kt`. **Depends on Task 2.** May run beside Tasks 3–6; must finish before Task 8.

- [x] **Step 1: Failing tests** in `McpToolInvokerTest`. Change the group list in `stats counts by each group it offers` to `listOf("verdict", "language", "problem", "part", "level")`, and add (import `kotlinx.serialization.json.double`):

```kotlin
    @Test
    fun `stats by part also counts the problems in each part`() {
        val invoker = invokerOver(
            aSubmissionRecord(
                lessonId = 1,
                part = "SELECT",
                verdict = Verdict.WRONG,
                ts = OffsetDateTime.parse("2026-10-01T10:00:00+09:00"),
            ),
            aSubmissionRecord(
                lessonId = 1,
                part = "SELECT",
                verdict = Verdict.PASS,
                ts = OffsetDateTime.parse("2026-10-01T10:05:00+09:00"),
            ),
        )

        val entry = structured(invoker.call("stats", arguments("groupBy" to "part")))["entries"]!!
            .jsonArray.single().jsonObject

        entry["count"]!!.jsonPrimitive.int shouldBe 2
        entry["attempted"]!!.jsonPrimitive.int shouldBe 1
        entry["passed"]!!.jsonPrimitive.int shouldBe 1
        entry["passedFirstSubmit"]!!.jsonPrimitive.int shouldBe 0
        entry["runsBeforePass"]!!.jsonPrimitive.double shouldBe 0.0
    }

    /** Absent, not zero: nothing in the bucket passed, so there is no median to report. */
    @Test
    fun `stats by level leaves out runs-before-pass when nothing passed`() {
        val invoker = invokerOver(aSubmissionRecord(level = 2, verdict = Verdict.WRONG))

        val entry = structured(invoker.call("stats", arguments("groupBy" to "level")))["entries"]!!
            .jsonArray.single().jsonObject

        entry["key"]!!.jsonPrimitive.content shouldBe "2"
        entry.shouldNotContainKey("runsBeforePass")
    }

    @Test
    fun `stats by verdict carries counts only`() {
        val payload = structured(invokerOver(aSubmissionRecord()).call("stats", arguments("groupBy" to "verdict")))

        payload["entries"]!!.jsonArray.single().jsonObject.shouldNotContainKey("attempted")
    }
```

- [x] **Step 2:** run `McpToolInvokerTest` → the three new tests FAIL.
- [x] **Step 3: Implement** in `McpToolInvoker` (imports `domain.calc.ProblemProgress`, `kotlinx.serialization.json.JsonObjectBuilder`):

```kotlin
    // An absent key is omitted rather than written as null — it means the grouping value
    // was never recorded, and `"key": null` would read like a bucket that has one.
    private fun bucketOf(bucket: TallyBucket): JsonObject = buildJsonObject {
        bucket.key?.let { put("key", it) }
        bucket.label?.let { put("label", it) }
        put("count", bucket.count)
        bucket.progress?.let { progressOf(it) }
    }

    // Counts of problems, beside the submit count, for part and level only (spec 2026-10-07 §4.3).
    private fun JsonObjectBuilder.progressOf(progress: ProblemProgress) {
        put("attempted", progress.attempted)
        put("passed", progress.passed)
        put("passedFirstSubmit", progress.passedFirstSubmit)
        progress.runsBeforePass?.let { put("runsBeforePass", it) }
    }
```

In `McpToolCatalog.stats()`, append to the description (before the closing `",`):

```kotlin
            " `part` and `level` buckets also count **problems**: `attempted` (submitted at least once), " +
            "`passed` (a passing submit), `passedFirstSubmit` (the first submit passed) and `runsBeforePass` — " +
            "the median number of runs, in any language, before a problem's first passing submit, absent " +
            "when nothing in the bucket passed. Problems, not (problem, language) pairs."
```

- [x] **Step 4:** run `McpToolInvokerTest`, `McpToolCatalogTest` → PASS.
- [x] **Step 5: Commit** `feat(mcp): stats by part and level, with problem counts` — `Refs #353`.

---

### Task 8: The `repair_steps` tool

**Files:** `McpToolCatalog.kt`, `McpToolInvoker.kt`, `McpRecordJson.kt`, tests `McpToolCatalogTest.kt`, `McpToolInvokerTest.kt`, `McpRecordJsonTest.kt`. **Depends on Tasks 6 and 7.**

- [x] **Step 1: Failing tests.**

`McpToolCatalogTest`: rename and extend the first test:

```kotlin
    @Test
    fun `exposes exactly the seven tools that can be answered from what ships`() {
        tools.map { it["name"]!!.jsonPrimitive.content }
            .shouldContainExactly(
                "submissions",
                "get_problem",
                "stats",
                "list_problems",
                "review_queue",
                "slow_passes",
                "repair_steps",
            )
    }
```

(Update its KDoc's "Six" to "Seven", and "all six descriptions" in the later KDoc to "all seven".) Add:

```kotlin
    @Test
    fun `repair_steps narrows by everything and requires nothing`() {
        properties("repair_steps").keys.shouldContainExactly(setOf("since", "language", "part", "lessonId", "limit"))
        required("repair_steps").shouldContainExactly()
        property("repair_steps", "lessonId")["type"]!!.jsonPrimitive.content shouldBe "integer"
    }

    /** The description is where a model learns a step is a fact, not a finding. */
    @Test
    fun `repair_steps says what it is not, and names the fields that qualify a diff`() {
        val description = tool("repair_steps")["description"]!!.jsonPrimitive.content

        description shouldContain "not"
        description shouldContain "noDiff"
        description shouldContain "codeLate"
    }
```

`McpRecordJsonTest` (imports `aRun`, `aCodedGrading`, `aTestcaseResult`, `LabelledStep`, `ProblemLabel`, `Transition`, `NoDiff`, `Verdict`, `jsonObject`):

```kotlin
    @Test
    fun `a repair step carries what failed, what followed, and why there is no diff`() {
        val tuple = "(1054, \"Unknown column 'x' in 'field list'\")"
        val failed = aRun(at = "2026-10-07T10:00:00+09:00", verdict = Verdict.COMPILE_ERROR)
            .copy(errorText = tuple, testcases = listOf(aTestcaseResult(passed = false, msg = tuple)))
        val followed = aRun(at = "2026-10-07T10:00:05+09:00", verdict = Verdict.PASS)
        val step = LabelledStep(
            ProblemLabel(lessonId = 120804, title = "t", level = 2, part = "SELECT"),
            Transition(aCodedGrading(failed, late = true), aCodedGrading(followed), diff = null, noDiff = NoDiff.SAME_CODE),
        )

        val json = McpRecordJson.repairSteps(listOf(step)).single().jsonObject

        json["part"]!!.jsonPrimitive.content shouldBe "SELECT"
        json["noDiff"]!!.jsonPrimitive.content shouldBe "sameCode"
        json.shouldNotContainKey("diff")
        val from = json["from"]!!.jsonObject
        from["recordId"]!!.jsonPrimitive.content shouldBe failed.recordId()
        from["action"]!!.jsonPrimitive.content shouldBe "run"
        from["errorText"]!!.jsonPrimitive.content shouldBe tuple
        from["failedMessage"]!!.jsonPrimitive.content shouldBe tuple
        from["failedCases"]!!.jsonPrimitive.int shouldBe 1
        from["totalCases"]!!.jsonPrimitive.int shouldBe 1
        from["codeLate"]!!.jsonPrimitive.booleanOrNull shouldBe true
        json["to"]!!.jsonObject.shouldNotContainKey("codeLate")
        json["to"]!!.jsonObject.shouldNotContainKey("errorText")
    }

    /** Absent is not zero: an unresolved grading has no verdict key, and says how it ended instead. */
    @Test
    fun `an unresolved side has no verdict, only its outcome`() {
        val unresolved = aRun(at = "2026-10-07T10:00:00+09:00", verdict = null, outcome = Outcome.UNKNOWN)
        val step = LabelledStep(
            ProblemLabel(lessonId = 120804, title = null, level = null, part = null),
            Transition(aCodedGrading(unresolved), aCodedGrading(aRun(at = "2026-10-07T10:00:05+09:00")), "d", null),
        )

        val json = McpRecordJson.repairSteps(listOf(step)).single().jsonObject

        json["from"]!!.jsonObject.shouldNotContainKey("verdict")
        json["from"]!!.jsonObject["outcome"]!!.jsonPrimitive.content shouldBe "UNKNOWN"
        json.shouldNotContainKey("title")
        json["diff"]!!.jsonPrimitive.content shouldBe "d"
    }
```

`McpToolInvokerTest` (imports `aRun`, `shouldContain` already present):

```kotlin
    @Test
    fun `repair_steps answers each failed grading with the attempt that followed it, newest first`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:01:00+09:00")
        val third = aRun(at = "2026-10-07T10:02:00+09:00", verdict = Verdict.PASS)
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(first, second, third)
                .withRunCode(first, "a").withRunCode(second, "b").withRunCode(third, "c").query(),
        )

        val payload = structured(invoker.call("repair_steps", JsonObject(emptyMap())))

        payload["count"]!!.jsonPrimitive.int shouldBe 2
        val newest = payload["steps"]!!.jsonArray.first().jsonObject
        newest["from"]!!.jsonObject["recordId"]!!.jsonPrimitive.content shouldBe second.recordId()
        newest["to"]!!.jsonObject["verdict"]!!.jsonPrimitive.content shouldBe "PASS"
        newest["diff"]!!.jsonPrimitive.content shouldContain "+c"
    }

    @Test
    fun `repair_steps says why a step has no diff`() {
        val first = aRun(at = "2026-10-06T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:00+09:00", verdict = Verdict.PASS)
        val invoker = McpToolInvoker(aRecordRepository(root).containing(first, second).withRunCode(second, "b").query())

        val step = structured(invoker.call("repair_steps", JsonObject(emptyMap())))["steps"]!!.jsonArray.single()

        step.jsonObject["noDiff"]!!.jsonPrimitive.content shouldBe "fromCodeUnknown"
    }

    @Test
    fun `repair_steps narrows to one lesson, quoted or not`() {
        val invoker = invokerOver(
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 1),
            aRun(at = "2026-10-07T10:00:00+09:00", lessonId = 2),
            aRun(at = "2026-10-07T10:00:01+09:00", lessonId = 2),
        )

        structured(invoker.call("repair_steps", arguments("lessonId" to "2")))["count"]!!.jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `repair_steps refuses a limit that is not a positive number`() {
        val result = invokerOver().call("repair_steps", arguments("limit" to 0))

        failed(result).shouldBeTrue()
        message(result).shouldContain("limit")
    }

    @Test
    fun `repair_steps refuses an argument it does not have`() {
        val result = invokerOver().call("repair_steps", arguments("verdict" to "WRONG"))

        failed(result).shouldBeTrue()
        message(result).shouldContain("verdict")
    }
```

- [x] **Step 2:** run the three classes → new tests FAIL.
- [x] **Step 3: Implement.**

`McpToolCatalog`: add `const val REPAIR_STEPS = "repair_steps"`, append it to `NAMES` and `add(repairSteps())` last in `definitions()`, and:

```kotlin
    private fun repairSteps(): JsonObject = tool(
        name = REPAIR_STEPS,
        title = "Each failed grading and the attempt that followed it",
        description = "Every grading that did not pass, paired with the next grading of the same problem in " +
            "the same language — run or submit — and the unified diff of the code between them, newest first " +
            "by the later grading. **A step is what changed, not a finding about what was wrong**: grouping " +
            "steps into habits is the reader's job, a pattern seen once is not a pattern, and cite the record " +
            "ids behind any you name. `from` carries the verdict (absent when it was never resolved — " +
            "`outcome` says how it ended), the error text and the failing-case counts; `to` carries what " +
            "followed. Consecutive gradings with identical code make no step. A step without `diff` says why " +
            "in `noDiff`: `fromCodeUnknown`, `toCodeUnknown` or `codeUnknown` when the code was not kept — " +
            "run code exists only from 2026-10-07, submit code from the start — `tooLarge` over 2000 lines, " +
            "or `sameCode`, which appears only beside a late side. `codeLate: true` on a side means its code " +
            "was attached after the problem's next grading was recorded, so it may be that grading's code; " +
            "the check cannot catch a second Run pressed within the ~0.3 s fetch, and submit code records no " +
            "fetch time so it is never marked. `since` bounds when the later grading was recorded.",
    ) {
        putJsonObject("properties") {
            putJsonObject("since") {
                put("type", "string")
                put("description", Since.FORMAT + ". Bounds when the later grading of a step was recorded.")
            }
            putJsonObject("language") {
                put("type", "string")
                put("description", "Keep only steps in this Programmers language id (java, python3, mysql, …).")
            }
            putJsonObject("part") {
                put("type", "string")
                put("description", "Keep only problems in this part, matched case-insensitively and in full.")
            }
            putJsonObject("lessonId") {
                put("type", "integer")
                put("description", "Keep only this lesson.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("minimum", 1)
                put("description", "Keep only this many, from the newest end.")
            }
        }
    }
```

`McpToolInvoker`:

```kotlin
        McpToolCatalog.REPAIR_STEPS -> executed { repairSteps(checked(arguments, REPAIR_ARGS)) }
```

```kotlin
    private fun repairSteps(arguments: JsonObject): JsonObject {
        val steps = query.repairSteps(
            since = arguments.text("since")?.let(Since::from),
            language = arguments.text("language"),
            part = arguments.text("part"),
            lessonId = optionalLessonId(arguments),
            limit = limitOf(arguments),
        )
        return buildJsonObject {
            put("count", steps.size)
            put("steps", McpRecordJson.repairSteps(steps))
        }
    }
```

Split `lessonIdOf` so the same rule serves an optional argument:

```kotlin
    private fun lessonIdOf(arguments: JsonObject): Long =
        optionalLessonId(arguments) ?: throw IllegalArgumentException("lessonId is required")

    // Lenient about the JSON type, strict about the value: models quote numbers routinely,
    // and refusing "120804" would fail a call that is not actually wrong.
    private fun optionalLessonId(arguments: JsonObject): Long? {
        val raw = arguments["lessonId"] ?: return null
        val id = (raw as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: throw IllegalArgumentException("lessonId must be a whole number")
        require(id > 0) { "lessonId must be positive" }
        return id
    }
```

and `val REPAIR_ARGS = setOf("since", "language", "part", "lessonId", "limit")` in the companion.

`McpRecordJson` (imports `domain.calc.CodedGrading`, `LabelledStep`, `ProblemLabel`, `Transition`, `kotlinx.serialization.json.JsonObjectBuilder`, `java.time.OffsetDateTime`, `java.time.format.DateTimeFormatter`):

```kotlin
    /**
     * Repair steps (spec 2026-10-07 §4.3): facts on both sides, and the diff or the reason there is
     * none. Absent stays absent — a problem recorded before the catalog was consulted has no `part`.
     */
    fun repairSteps(steps: List<LabelledStep>): JsonArray = JsonArray(steps.map(::repairStep))

    private fun repairStep(labelled: LabelledStep): JsonObject = buildJsonObject {
        labelOf(labelled.problem)
        put("language", labelled.step.to.record.language)
        put("from", failedSide(labelled.step.from))
        put("to", sideOf(labelled.step.to))
        diffOf(labelled.step, "diff")
    }

    private fun JsonObjectBuilder.labelOf(problem: ProblemLabel) {
        put("lessonId", problem.lessonId)
        problem.title?.let { put("title", it) }
        problem.part?.let { put("part", it) }
        problem.level?.let { put("level", it) }
    }

    private fun failedSide(grading: CodedGrading): JsonObject {
        val record = grading.record
        val failure = buildJsonObject {
            record.errorText?.let { put("errorText", it) }
            firstFailedMessage(record)?.let { put("failedMessage", it) }
            put("failedCases", record.tcSummary.failed)
            put("totalCases", record.tcSummary.total)
        }
        return JsonObject(sideOf(grading) + failure)
    }

    // `codeLate` only when the check found it late; a side with no fetch time was never checked.
    private fun sideOf(grading: CodedGrading): JsonObject = buildJsonObject {
        put("recordId", grading.record.recordId())
        put("ts", isoOf(grading.record.ts))
        put("action", grading.record.action.name.lowercase())
        put("outcome", grading.record.outcome.name)
        grading.record.verdict?.let { put("verdict", it.name) }
        if (grading.late) put("codeLate", true)
    }

    private fun firstFailedMessage(record: SubmissionRecord): String? =
        record.testcases.sortedBy { it.id }.firstOrNull { it.hasFailed() }?.msg

    private fun JsonObjectBuilder.diffOf(transition: Transition, key: String) {
        transition.diff?.let { put(key, it) }
        transition.noDiff?.let { put("noDiff", it.wireName()) }
    }

    private fun isoOf(at: OffsetDateTime): String = at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
```

- [x] **Step 4:** run `McpToolCatalogTest`, `McpRecordJsonTest`, `McpToolInvokerTest`, `McpDispatcherTest`, `McpControllerTest` → PASS (the last two count `NAMES.size`).
- [x] **Step 5: Commit** `feat(mcp): repair_steps — each failure and the attempt that followed it` — `Refs #353`.

---

### Task 9: `get_problem(include)` (D10)

**Files:** `McpToolCatalog.kt`, `McpToolInvoker.kt`, `McpRecordJson.kt`, tests. **Depends on Task 8.** Disjoint from Task 10.

- [x] **Step 1: Failing tests.**

`McpToolCatalogTest`:

```kotlin
    @Test
    fun `get_problem offers code as an optional include`() {
        val include = property("get_problem", "include")

        include["type"]!!.jsonPrimitive.content shouldBe "array"
        include["items"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
            .shouldContainExactly("code", "runs")
        required("get_problem").shouldContainExactly("lessonId")
    }
```

`McpToolInvokerTest` (imports `kotlinx.serialization.json.putJsonArray`, `kotlinx.serialization.json.add`):

```kotlin
    @Test
    fun `get_problem with include code puts each submit's code on it, and nothing on runs`() {
        val run = aRun(at = "2026-10-07T09:59:00+09:00")
        val submit = aSubmit(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(run, submit)
                .withSubmitCode(submit, "select 1\n").withRunCode(run, "select 0").query(),
        )

        val items = itemsOf(invoker.call("get_problem", includeArguments("code")))

        items.single { it.isSubmit() }["code"]!!.jsonPrimitive.content shouldBe "select 1\n"
        items.single { !it.isSubmit() }.shouldNotContainKey("code")
    }

    @Test
    fun `get_problem with include runs puts each run's code and its diff from the grading before it`() {
        val first = aRun(at = "2026-10-07T10:00:00+09:00")
        val second = aRun(at = "2026-10-07T10:00:05+09:00", verdict = Verdict.PASS)
        val invoker = McpToolInvoker(
            aRecordRepository(root).containing(first, second)
                .withRunCode(first, "a").withRunCode(second, "b").query(),
        )

        val items = itemsOf(invoker.call("get_problem", includeArguments("runs")))
        val newest = items.first()

        newest["code"]!!.jsonPrimitive.content shouldBe "b"
        newest.shouldContainKey("codeFetchedAt")
        newest["diffFromPrevGrading"]!!.jsonPrimitive.content shouldContain "+b"
        items.last().shouldNotContainKey("diffFromPrevGrading")
    }

    /** The default answer must not change shape for clients that never ask for code. */
    @Test
    fun `get_problem without include carries no code`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(aRecordRepository(root).containing(run).withRunCode(run, "a").query())

        itemsOf(invoker.call("get_problem", arguments("lessonId" to 120804))).single().shouldNotContainKey("code")
    }

    @Test
    fun `get_problem takes a bare include string as one value`() {
        val run = aRun(at = "2026-10-07T10:00:00+09:00")
        val invoker = McpToolInvoker(aRecordRepository(root).containing(run).withRunCode(run, "a").query())

        val items = itemsOf(invoker.call("get_problem", arguments("lessonId" to 120804, "include" to "runs")))

        items.single()["code"]!!.jsonPrimitive.content shouldBe "a"
    }

    @Test
    fun `get_problem refuses an include it does not offer`() {
        val result = invokerOver().call("get_problem", includeArguments("returned"))

        failed(result).shouldBeTrue()
        message(result).shouldContain("include")
    }

    private fun includeArguments(vararg values: String): JsonObject = buildJsonObject {
        put("lessonId", 120804)
        putJsonArray("include") { values.forEach { add(it) } }
    }

    private fun itemsOf(result: JsonObject): List<JsonObject> =
        structured(result)["submissions"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.isSubmit(): Boolean = this["action"]!!.jsonPrimitive.content == "submit"
```

(`maps.shouldContainKey` import may be needed.)

- [x] **Step 2:** run → new tests FAIL.
- [x] **Step 3: Implement.**

`McpToolCatalog`: constants and schema.

```kotlin
    const val INCLUDE_CODE = "code"
    const val INCLUDE_RUNS = "runs"
    val INCLUDES = listOf(INCLUDE_CODE, INCLUDE_RUNS)
```

In `getProblem()`'s `properties`, after `lessonId`:

```kotlin
            putJsonObject("include") {
                put("type", "array")
                put("description", "Add code: `code` on each submit; `runs` on each run, with its diff.")
                putJsonObject("items") {
                    put("type", "string")
                    putJsonArray("enum") { INCLUDES.forEach { add(it) } }
                }
            }
```

and append to its description (before `ELAPSED_MEANS`):

```kotlin
            " `include` adds code without changing anything else: `code` puts each submit's code on it, " +
            "`runs` puts on each run its `code`, `codeFetchedAt`, `codeLate` and `diffFromPrevGrading` — " +
            "the diff from the grading before it in the same language, run or submit — or `noDiff` saying " +
            "why there is none (the first grading in a language has neither). Run code exists only from " +
            "2026-10-07." +
```

`McpToolInvoker`:

```kotlin
    private fun problem(arguments: JsonObject): JsonObject {
        val lessonId = lessonIdOf(arguments)
        val include = includeOf(arguments)
        val history = query.problem(lessonId)
        if (include.isEmpty()) return McpRecordJson.problem(history)
        return McpRecordJson.problem(history, query.codedProblem(lessonId), include)
    }

    // Lenient about the JSON type — an array, or one bare string — and strict about the values.
    private fun includeOf(arguments: JsonObject): Set<String> {
        val raw = arguments["include"] ?: return emptySet()
        val values = (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
            ?: listOf((raw as? JsonPrimitive)?.contentOrNull)
        return values.map(::includeValue).toSet()
    }

    private fun includeValue(raw: String?): String =
        raw?.trim()?.lowercase()?.takeIf { it in McpToolCatalog.INCLUDES }
            ?: throw IllegalArgumentException("include takes ${McpToolCatalog.INCLUDES.joinToString()}")
```

and `PROBLEM_ARGS = setOf("lessonId", "include")`.

`McpRecordJson` (import `application.CodedProblem`):

```kotlin
    /**
     * One problem in full, with the code [include] asks for (spec 2026-10-07 §4.3). Every key the
     * default answer has stays where it was; code is added to the items it belongs to, never in a
     * second array that would repeat every run.
     */
    fun problem(history: ProblemHistory, coded: CodedProblem, include: Set<String>): JsonObject {
        val items = history.submissions.map { JsonObject(full(it) + codeFor(it, coded, include)) }
        return JsonObject(problem(history) + ("submissions" to JsonArray(items)))
    }

    private fun codeFor(record: SubmissionRecord, coded: CodedProblem, include: Set<String>): JsonObject {
        val wantsSubmit = record.isSubmission() && McpToolCatalog.INCLUDE_CODE in include
        val wantsRun = !record.isSubmission() && McpToolCatalog.INCLUDE_RUNS in include
        if (wantsSubmit) return submitCode(coded.codeOf(record))
        if (wantsRun) return runCode(coded.codeOf(record), coded.transitionInto(record))
        return JsonObject(emptyMap())
    }

    private fun submitCode(grading: CodedGrading?): JsonObject = buildJsonObject {
        grading?.code?.let { put("code", it.text) }
    }

    private fun runCode(grading: CodedGrading?, transition: Transition?): JsonObject = buildJsonObject {
        grading?.code?.let { put("code", it.text) }
        grading?.code?.fetchedAt?.let { put("codeFetchedAt", isoOf(it)) }
        if (grading?.late == true) put("codeLate", true)
        transition?.let { diffOf(it, "diffFromPrevGrading") }
    }
```

(`JsonObject(map + map)` keeps insertion order, so `submissions` stays at its position and the default keys keep theirs.)

- [x] **Step 4:** run `McpToolCatalogTest`, `McpToolInvokerTest`, `McpRecordJsonTest` → PASS.
- [x] **Step 5: Commit** `feat(mcp): get_problem includes submit and run code on request` — `Refs #353`.

---

### Task 10: The model is told how to read a step (D12)

**Files:** `McpDispatcher.kt`, `McpInstructionsTest.kt`. **Depends on Task 8.** Disjoint from Task 9.

- [x] **Step 1: Failing test** in `McpInstructionsTest`:

```kotlin
    /** Spec 2026-10-07 §4.3: a diff is evidence of a change, and two fields say when not to trust it. */
    @Test
    fun `it says what a repair step is, and the fields that qualify its diff`() {
        prose shouldContain "what changed, not what was wrong"
        instructions shouldContain "noDiff"
        instructions shouldContain "codeLate"
    }
```

(`it names every tool` already fails now — `repair_steps` is in `NAMES` and not in the text.)

- [x] **Step 2:** run → two FAIL.
- [x] **Step 3: Implement** — three edits inside `INSTRUCTIONS`:

```
- stats: counts per verdict, language, problem, part or level. Counts only.
```
(replacing the `stats` line), after the `slow_passes` line:
```
- repair_steps: each failed grading, the next one in its language, and the code diff.
```
and before the `statement`/`kind` reading:
```
- A repair step shows what changed, not what was wrong. Run code is kept only since
  2026-10-07; `noDiff` says why a step has none, and `codeLate` marks code that may
  belong to the next grading.
```

- [x] **Step 4:** run `McpInstructionsTest` → PASS, including `it stays short enough for a client to show in full` (~2,905 of 3,000). If that one fails, shorten the new lines — do not raise the cap (D12).
- [x] **Step 5: Commit** `feat(mcp): tell the model how to read a repair step` — `Refs #353`.

---

### Task 11: Docs, ADR, spec, progress

**Depends on all code tasks.** Docs only.

- [x] **Step 1: `docs/mcp.md`.**
  - Opening blockquote: `**Six tools, none of which write.** Four hand back stored records and counts; two compute …` → `**Seven tools, none of which write.** Five hand back stored records, counts and the code between them; two compute …`.
  - `## The six tools` → `## The seven tools`; `Four return stored records and counts and nothing else.` → `Five return stored records, counts and the code between them, and nothing else.`
  - Table: `get_problem` arguments `lessonId` · `include?`, answer gains "`include` adds each submit's or run's code."; `stats` arguments `` `groupBy` — `verdict` · `language` · `problem` · `part` · `level` ``, answer gains "`part`/`level` also count problems."; new last row:

```
| `repair_steps` | `since?` · `language?` · `part?` · `lessonId?` · `limit?` | Each grading that did not pass, the next grading of the problem in its language, and the code diff between them, newest first. **What changed, not what was wrong.** |
```

  - New section after "What `slow_passes` will not decide for you":

```markdown
### What `repair_steps` hands over

A repair step is a grading that did not pass and the next grading of the same problem **in the same
language** — run or submit — with the unified diff of the code between them. It is what changed after a
failure, which is the evidence a recurring mistake leaves: an argument-order slip often compiles and
prints nothing, and the diff is its only witness
([spec](superpowers/specs/2026-10-07-mistake-patterns-design.md)).

It is **not** a finding about what was wrong. Grouping steps into habits, naming them and deciding
which recur is the reader's work, and nothing concluded is stored
([`decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored`](llm-wiki/wiki/decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored.md)).

- **"Did not pass" includes "never resolved".** Such a step's `from` has no `verdict` and says how it
  ended in `outcome`. On the author's log, 25 of 65 candidate steps on 2026-10-07 start that way —
  SQL failures recorded before #350 classified them.
- **Identical code makes no step** — nothing was corrected — unless a side is marked `codeLate`.
- **No `diff` comes with a reason** in `noDiff`: `fromCodeUnknown` · `toCodeUnknown` · `codeUnknown`
  (the code was not kept), `tooLarge` (over 2000 lines on a side), or `sameCode` (only beside a late side).
- **Run code is kept from 2026-10-07.** Submit code has always been in `attempts/`, so a step between two
  submits has a diff whenever it happened; a step involving an older run does not.
- **`codeLate: true`** on a side means its code was attached after the problem's next grading was
  recorded, so it may be that grading's code. It cannot catch a second Run pressed within the ~0.3 s the
  fetch takes, and submit code records no fetch time, so it is never marked.
- `since` bounds when the **later** grading was recorded, and the list is newest first by that time.

`get_problem` takes `include` for the same code on one problem: `code` adds each submit's code, and
`runs` adds each run's `code`, `codeFetchedAt`, `codeLate` and `diffFromPrevGrading` — the diff from the
grading before it in the same language — or `noDiff`. Without `include` the answer is unchanged.

`stats` grouped by `part` or `level` also counts problems per bucket: `attempted` (submitted at least
once), `passed`, `passedFirstSubmit`, and `runsBeforePass` — the median number of runs before a problem's
first passing submit, absent when nothing in the bucket passed. Problems, not (problem, language) pairs.
```

  - "What is not built" → under "Genuinely absent", add: `` `get_problem(include=returned)` — the table a failed SQL run returned, read from `.ps/raw/` — is designed (spec §4.3) and deferred to its own plan: it needs protocol parsing on the read path. ``

- [x] **Step 2: `docs/mcp.ko.md`** — the same changes in Korean, at the same places:
  - `**툴 여섯 개, 쓰는 것은 하나도 없습니다.** 넷은 저장된 기록과 집계를 그대로 돌려주고,` → `**툴 일곱 개, 쓰는 것은 하나도 없습니다.** 다섯은 저장된 기록과 집계, 그 사이의 코드를 그대로 돌려주고,`; `## 여섯 개의 툴` → `## 일곱 개의 툴`; `넷은 저장된 기록과 집계만 돌려줍니다.` → `다섯은 저장된 기록과 집계, 그 사이의 코드만 돌려줍니다.`
  - Table rows: `get_problem` 인자 `lessonId` · `include?`, 끝에 "`include` 로 제출·실행 코드를 덧붙입니다."; `stats` 인자에 `` · `part` · `level` ``, 끝에 "`part`/`level` 은 문제 수도 셉니다."; new row:

```
| `repair_steps` | `since?` · `language?` · `part?` · `lessonId?` · `limit?` | 통과하지 못한 채점마다, 같은 언어로 그 문제를 다음에 채점한 기록과 둘 사이의 코드 diff. 최신순. **무엇이 바뀌었는지이지, 무엇이 틀렸는지가 아닙니다.** |
```

  - New section, after the `slow_passes` section:

```markdown
### `repair_steps` 가 넘겨주는 것

repair step 은 통과하지 못한 채점과, 같은 문제를 **같은 언어로** 다음에 채점한 기록(run 이든 submit 이든),
그리고 둘 사이 코드의 unified diff 입니다. 실패 다음에 무엇이 바뀌었는지 — 반복되는 실수가 남기는 증거가
바로 이것입니다. 인자 순서 실수는 컴파일도 되고 아무것도 출력하지 않는 경우가 많아서, diff 가 유일한
증인입니다 ([설계](superpowers/specs/2026-10-07-mistake-patterns-design.md)).

무엇이 틀렸는지에 대한 **판정이 아닙니다.** step 들을 습관으로 묶고 이름 붙이고 무엇이 반복되는지 정하는
일은 읽는 쪽의 몫이고, 그 결론은 어디에도 저장되지 않습니다
([`decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored`](llm-wiki/wiki/decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored.md)).

- **"통과하지 못함"에는 "판정되지 않음"도 포함됩니다.** 그런 step 의 `from` 에는 `verdict` 가 없고, 어떻게
  끝났는지는 `outcome` 이 말합니다. 2026-10-07 작성자의 기록에서 후보 step 65개 중 25개가 이렇게
  시작합니다 — #350 이전에 기록된 SQL 실패들입니다.
- **코드가 같으면 step 이 아닙니다** — 고친 것이 없으니까요. 단, 한쪽에 `codeLate` 가 붙어 있으면 남습니다.
- **diff 가 없으면 이유가 `noDiff` 에 있습니다**: `fromCodeUnknown` · `toCodeUnknown` · `codeUnknown`
  (코드가 보관되지 않음), `tooLarge` (한쪽이 2000줄 초과), `sameCode` (late 인 쪽 옆에서만).
- **run 코드는 2026-10-07부터 보관됩니다.** submit 코드는 처음부터 `attempts/` 에 있으므로, 두 submit 사이의
  step 은 언제 일어났든 diff 가 있고, 그 이전의 run 이 낀 step 에는 없습니다.
- 한쪽의 **`codeLate: true`** 는 그 코드가 문제의 다음 채점이 기록된 뒤에 붙었다는 뜻이고, 따라서 그 다음
  채점의 코드일 수 있습니다. 코드를 가져오는 약 0.3초 안에 Run 을 다시 누른 경우는 잡지 못하며, submit
  코드는 가져온 시각이 기록되지 않아 표시되지 않습니다.
- `since` 는 **뒤쪽** 채점이 기록된 시각을 제한하고, 목록은 그 시각 기준 최신순입니다.

`get_problem` 은 `include` 로 한 문제의 같은 코드를 줍니다: `code` 는 submit 마다 코드를, `runs` 는 run 마다
`code`·`codeFetchedAt`·`codeLate`·`diffFromPrevGrading`(같은 언어의 바로 앞 채점과의 diff) 또는 `noDiff` 를
덧붙입니다. `include` 가 없으면 응답은 그대로입니다.

`stats` 를 `part` 나 `level` 로 묶으면 버킷마다 문제 수도 셉니다: `attempted`(한 번 이상 제출),
`passed`, `passedFirstSubmit`, 그리고 `runsBeforePass` — 첫 통과 제출 전 run 횟수의 중앙값이며, 버킷에
통과한 문제가 없으면 빠집니다. (문제, 언어) 쌍이 아니라 문제 단위입니다.
```

  - "완전히 없는 것" 에: `` `get_problem(include=returned)` — 실패한 SQL run 이 돌려준 표를 `.ps/raw/` 에서 읽는 것 — 은 설계(§4.3)에 있으나 별도 계획으로 미뤘습니다. 읽는 쪽에서 프로토콜 파싱이 필요하기 때문입니다. ``
  - First line: `git hash-object docs/mcp.md` → `<!-- translated-from: mcp.md@<that hash> -->` (after Step 1 is final).

- [x] **Step 3: README.** `README.md` row: `six read tools` → `seven read tools`; `README.ko.md`: `읽기 툴 6개` → `읽기 툴 7개`; update `README.ko.md`'s first line to `git hash-object README.md`. Run `scripts/guards.sh` → passes.

- [x] **Step 4: Spec** `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md`:
  - §4.2 bullet "MCP may read them from there (4.3)." → "MCP may read them from there; deferred from 4.3 to its own plan."
  - §4.3, after the MCP surface table, add:

```markdown
**As built** (plan `docs/superpowers/plans/2026-10-07-repair-steps-over-mcp.md`):

- The calculator takes one problem's whole timeline and pairs per language, because the late check
  compares with the problem's next record in any language. `codeLate: true` marks a side; a late
  side keeps a step even when its code matches (`noDiff: "sameCode"`).
- "Failed" is "did not pass", unresolved verdicts included — 25 of 65 measured candidate steps.
- A step without a diff says why in `noDiff` (`fromCodeUnknown` · `toCodeUnknown` · `codeUnknown` ·
  `sameCode` · `tooLarge`). Submit code is never marked late: nothing records when it was fetched.
- `get_problem(include)` adds code to the existing items (`code`; and for runs `codeFetchedAt`,
  `codeLate`, `diffFromPrevGrading`/`noDiff`). **`returned` is deferred** to its own plan.
- The `stats` problem counts appear on `part` and `level` buckets only, and count problems.
```

  - §6 row 4.3, tests column: append "; `CodeTimeline` late rule (any language, never for submits); `RepairStepFilter`; `FileGradingCodes` torn line and path escape; `get_problem(include)`; `stats` part/level".

- [x] **Step 5: ADR** `docs/llm-wiki/wiki/decisions/2026-10-07-repair-steps-are-served-not-judged.md` (rename to the actual date if executed later; update links accordingly):

```markdown
---
type: decision
project: programmers-tracker
tags: [mcp, diagnosis, calculator, code, interpretation-boundary]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# Repair steps are served, not judged

## Context

[[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]] chose to hand the AI repair
steps — a failed grading, the next attempt, and the diff — and let it find habits.
[[decisions/2026-10-07-every-run-keeps-its-code]] made the runs' code exist. This decides the shape
of a step and what the server may and may not say about one. Measured on the live log 2026-10-07:
131 gradings, 65 candidate steps, 25 of them starting at a grading whose verdict was never resolved,
none between two submits.

## Options considered

1. **Pair per language, given one language** (the spec's wording). Cannot apply the late check, which
   compares with the problem's next record in any language.
2. **Pair per language over the whole problem**, late flags computed first — chosen.
3. **Drop steps whose code is unknown or late.** Rejected: silently drops evidence, and for a late side
   drops exactly the race signature.

## Decision

Option 2, as two pure calculators in `domain/calc`: `CodeTimeline` (code + `late`) and `RepairSteps`
(consecutive pairs per language, diffed). A step starts at any grading that did not pass, unresolved
included. Identical code makes no step unless a side is late. A missing diff is named in `noDiff`.
The LCS diff moved from the store into `domain/calc` so both attempt diffs and steps use one
implementation. `stats` part/level buckets count problems. `returned` is deferred.

## Rationale

A step is a recorded fact; every field on it is either stored or computed by a stated rule, which
keeps the server on the counting side of [[decisions/2026-08-12-the-server-counts-and-names-nothing]].
Naming every absence (`noDiff`, absent `verdict` beside `outcome`, `codeLate`) is
[[concepts/assumption-vs-measurement]] applied to a new surface.

## Accepted costs

- **Submit code is never checked for lateness** — nothing records when it was fetched.
- **The ~0.3 s race is still invisible**; `codeLate` catches late attachments only.
- **Historical steps are mostly `codeUnknown`**: runs have code only from 2026-10-07.
- `errorText` is returned whole; `limit` is the only bound on answer size.
- The instructions string sits ~95 characters under its 3,000 cap; 4.4 must make room, not raise it.

## Outcome

#353. Live acceptance pending: one problem solved with several failing runs and a pass;
`repair_steps(lessonId=…)` shows every correction with a diff and no `codeLate`.
```

- [x] **Step 6:** In `2026-10-07-mistake-patterns-are-diagnosed-not-stored.md`, Accepted costs, after the bullet ending `` `codeUncertain` instead of preventing it. `` add: `  (Superseded in the build: runs carry \`codeFetchedAt\` — [[decisions/2026-10-07-every-run-keeps-its-code]] — and steps mark a late side \`codeLate\` — [[decisions/2026-10-07-repair-steps-are-served-not-judged]].)` and bump `updated:`.
- [x] **Step 7:** `docs/llm-wiki/index.md` Decisions: `- 2026-10-07 [[decisions/2026-10-07-repair-steps-are-served-not-judged]] — repair_steps pairs each non-pass with the next grading in its language and names every missing diff; get_problem(include), stats by part/level; returned deferred`. `.harness/state/progress.md`: an entry `## 2026-10-07 — #353 repair steps over MCP (branch feat/ISSUE-repair-steps-over-mcp)` with the measured numbers, the tasks' commits, and "Pending: live — repair_steps on a freshly solved problem". Tick this plan's boxes.
- [x] **Step 8:** `scripts/guards.sh` → passes. Commit `docs: repair steps over MCP — ADR, mcp.md, spec as built` — `Closes #353`. *(As executed: the commit carries `Refs #353`; the PR body carries `Closes #353`.)*

---

### Task 12: Gates, PR, live

- [ ] `./scripts/check.sh && ./scripts/test.sh && ./scripts/build.sh && ./gradlew verifyBranchCoverage` → all exit 0.
- [ ] `/pull-request`; watch CI to completion; squash-merge; rebuild: `docker compose build && docker compose up -d --force-recreate`.
- [ ] **Live (spec §6):** the owner solves one problem with at least two failing runs that change the code, then a pass. Call `repair_steps(lessonId=<it>)`: one step per correction, each with `diff`, none `codeLate`. Call `get_problem(lessonId=<it>, include=["code","runs"])`: every run carries `code` and all but the first `diffFromPrevGrading`. Call `stats(groupBy="part")`: the part bucket carries `attempted`/`passed`. Record the time and numbers in the ADR's Outcome on the next branch.

## Self-review against the spec (part 4.3)

| Spec item | Here |
|---|---|
| `RepairSteps`, pure, zero mocks | Task 3 (`CodeTimeline` + `RepairSteps`), zero-mock tests |
| Code joined from `runs.jsonl` by `recordId` | Task 5 (`FileGradingCodes.runs`), Task 6 (`codesOf`) |
| Submit code from `attempts/` | Task 5 (`submitted` via `codePath`), Task 6 |
| Run code unreliable when `codeFetchedAt` > next record's `ts` | Task 3 (`CodeTimeline.isLate`, any language — D2), surfaced as `codeLate` (Tasks 8, 9) |
| Unknown code → no `diff`, says so, never dropped, never paired across the gap | Task 3 (`NoDiff`, `a gap in the code is never bridged`) |
| Identical consecutive code makes no step | Task 3 (`repeated identical runs make no step`; late exception D4) |
| Final step ends at the passing grading | Task 3 (consecutive pairing; `failed run followed by a passing run`) |
| Step fields: from {recordId, ts, action, verdict, errorText, failed/total}, to {…}, diff | Task 8 (`McpRecordJson.repairSteps`) + `failedMessage`, `outcome` |
| Tests: failed→passed, failed→failed, unknown either side, repeated identical, run→submit | Task 3, one test each |
| `repair_steps(since, language, part, lessonId, limit)`, newest first | Tasks 4, 6, 8 |
| `get_problem` `include`: `code`, `runs`; default unchanged | Task 9 (D10) |
| `get_problem` `include`: `returned` | **Deferred** (D13) — own plan |
| `stats` `groupBy` `part`, `level`; `attempted`, `passed`, `passedFirstSubmit`, `runsBeforePass` (median) | Tasks 2, 7 (D11) |
| Counts or stored facts only; nothing ranks or names a weakness | Descriptions (Tasks 7–9), instructions (Task 10), ADR (Task 11) |
| MCP read-only | `GradingCodes` port read-only; `FileGradingCodes` holds no writer (D16) |
| Docs: `mcp.md` + twin, instructions, ADR, progress, spec §6 | Tasks 10, 11 |
| Live acceptance: `repair_steps(lessonId=…)` shows every correction | Task 12 |
