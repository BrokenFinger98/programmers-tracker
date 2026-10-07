---
type: decision
project: programmers-tracker
tags: [mcp, diagnosis, calculator, code, security, interpretation-boundary]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# Repair steps are served, not judged

## Context

[[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]] chose to hand the AI repair
steps — a failed grading, the next attempt, and the diff — and let it find the habits.
[[decisions/2026-10-07-every-run-keeps-its-code]] made the runs' code exist. Part 4.3 of
`docs/superpowers/specs/2026-10-07-mistake-patterns-design.md` had to settle the shape of a step,
what the server may say about one, and how the code behind it reaches a client — under rules that
were already fixed: the server names no mistake
([[decisions/2026-08-12-the-server-counts-and-names-nothing]]), MCP stays read-only
([[decisions/2026-08-06-mcp-read-slice]]), and a value never recorded is absent, not filled in.

Measured on the live log before planning (2026-10-07, resolved per `(ts, captureKey)` as
`RecordHistory` does; `docs/superpowers/plans/2026-10-07-repair-steps-over-mcp.md`): 131
gradings, 65 candidate steps, **25 of them starting at a grading whose verdict was never
resolved**, none between two submits; 36 submits, every `codePath` readable; one `runs.jsonl` of
three lines, each joined to the log by `recordId`, none attached late.

Two things found in review changed the frame.

- **The client cuts what we send.** Claude Code cuts MCP server instructions and every tool
  description at 2,048 characters and keeps the head — its CHANGELOG 2.1.84: "MCP tool descriptions
  and server instructions are now capped at 2KB…"; 2.1.280: added
  `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` "to change the 2,048-character cap…". Verified on 2.1.285
  with the variable unset. Our own test allowed 3,000 and passed while the text was being cut: the
  instructions have been 2,607 characters since #287, and a session's system prompt on 2026-10-07
  shows them ending "there is no cohort here… [truncated]" — character 2,048 exactly. The branch's
  own additions had grown them to 2,921, with `get_problem` at 2,548 and `repair_steps` at 2,126.
- **A path a log line carries is now followed.** Serving submit code means reading the file a
  record's `codePath` names, from a root that also holds `.ps/git-credentials` (the push token), the
  `/watch` token and the raw frames — and git stores symbolic links, so a link can arrive with a
  clone or a pull, not only by hand.

## Options considered

The plan's decision table (D1–D16) and the review rounds on #353 are the record; only what was
weighed there is listed.

1. **Where pairing happens.** (a) One language at a time, as the spec worded it — cannot apply the
   late check, which compares a fetch with the problem's next record in any language. (b) The
   problem's whole timeline, late flags computed first, pairing per language inside — chosen (D2).
2. **What starts a step.** (a) Only resolved failures — drops 25 of the 65 measured candidates, the
   SQL failures recorded before #350, which are lesson 273711's class of evidence. (b) Give an
   unresolved grading a verdict — invents one. (c) Any grading that did not pass, with `outcome`
   beside an absent `verdict` — chosen (D3).
3. **Steps whose code is unknown, identical or late.** (a) Drop them — a silent drop, and for a late
   side it drops exactly the race's signature. (b) Keep them and name why there is no diff — chosen
   (D4, D5). Identical code with neither side late still makes no step: nothing was corrected.
4. **Where the diff is computed.** (a) In the adapter — splits the calculator, which must still
   compare code to know "identical". (b) A second LCS in the domain — two diffs formatting one
   change differently. (c) Move the store's diff, always a pure function, into `domain/calc` —
   chosen (D1).
5. **Lateness for submit code.** The attempt file's mtime as a fetch time — rejected as a guess (D6).
6. **Answer size.** (a) No default `limit`, as first planned (D9) — one argument-less call returns
   every step on record, without bound. (b) A default of 20, with the answer saying when it cut —
   chosen in review.
7. **How `get_problem` carries code.** (a) A parallel `runs` array — repeats every run record.
   (b) Enrich the existing items — chosen (D10), with the diff into a run named
   `diffFromPrevGrading`, apart from the submit-to-submit `diffFromPrev`, because the two compare
   against different neighbours.
8. **Where problem counts appear.** (a) On every `stats` bucket — a problem "passed" inside the
   WRONG bucket means nothing, and `problem` buckets would repeat `list_problems`. (b) On `part`
   and `level` buckets only — chosen (D11).
9. **Argument faults and our faults.** (a) Every `IllegalArgumentException` as a tool error, the
   path the older tools use — an invariant breaking behind valid arguments would tell the model to
   correct arguments that were fine. (b) Read and refuse every argument first, and report anything
   after that as our fault — chosen in review.
10. **The bound on reading kept code.** (a) The repository root, as planned (D15) — a `codePath`
    edited to `.ps/git-credentials` returned the push token. (b) `problems/`, lexically — a link
    under it still led out, because the path it is reached by is `problems/...`. (c) The real path
    under the real `problems/` — with `problems` itself linked to `.ps`, the bound moved with the
    link and the token came back again. (d) A regular file whose real path lies under the real
    root plus `problems` by name, a linked `problems` never followed — chosen.
11. **The text budget.** (a) Keep the 3,000-character cap (D12) — it passed while the client cut the
    text. (b) Ask readers to raise `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` — a public server cannot
    depend on a setting on the reader's side. (c) Fit every text within 2,000 characters as sent,
    pinned by tests — chosen in review.
12. **`returned`**, the table a failed SQL run returned. (a) Serve it now. (b) Defer it to its own
    plan — chosen (D13).

## Decision

- **Two pure calculators in `domain/calc`.** `CodeTimeline` attaches each grading's kept code and
  marks it late when it was fetched after the problem's next record, in any language. `RepairSteps`
  pairs consecutive gradings per language and diffs their code (trailing newlines trimmed). A step
  starts at any grading that did not pass, unresolved included; its `from` always carries `outcome`
  and carries `verdict` only when resolved. Identical code makes no step unless a side is late. A
  missing diff is named in `noDiff` (`fromCodeUnknown` · `toCodeUnknown` · `codeUnknown` ·
  `sameCode` · `tooLarge`); a diff cut at 400 lines carries `diffTruncated: true`; the failing
  side's case counts carry `casesComplete: false` when some cases never arrived. The diff moved
  from the store into `domain/calc`, so attempt diffs and steps share one implementation.
- **`repair_steps(since, language, part, lessonId, limit)`.** Pairing runs over each problem's
  whole history, then the filter applies; `since` bounds the later grading and the list is newest
  first by it. Without `limit` the adapter applies 20, and the answer carries `count`, `total`, and
  `truncated: true` only when `total` exceeds `count`. Arguments are read before anything else and
  a refused one comes back as a tool error (`isError`) in the filter's own words; a JSON `null` is
  "not given", here and in `get_problem`'s `include`. Past the arguments, an invariant breaking is
  rethrown as our fault and answered as an internal error (HTTP 500, JSON-RPC `-32603`) carrying
  nothing of the exception.
- **`get_problem(include=["code","runs"])` enriches the existing items.** Submits get `code`; runs
  get `code`, `codeFetchedAt`, `codeLate`, `diffFromPrevGrading` or `noDiff`, `diffTruncated`, and
  `fromCodeLate` when the earlier side's code was late — the doubt shown on the item whose diff used
  that code. `include` is a closed set (`ProblemInclude`). Without it the answer is byte-identical
  to before, and the log is read once either way.
- **`stats(groupBy=part|level)` counts problems.** Each bucket adds `attempted`, `passed`,
  `passedFirstSubmit` (the first submit resolved PASS) and `runsBeforePass` (the median over passed
  problems of the runs before the first passing submit; absent when nothing passed), per problem
  and keyed by each problem's newest known part or level. `count` stays the submits. The caveat that
  a `runsBeforePass` of 0 can mean no run was recorded is in the `stats` description only.
- **Kept submit code is read only from `<real records root>/problems`**: lexically bounded in
  `RecordLayout.recordFile`, then by real path in `FileGradingCodes.submitted`, regular files only,
  and a linked `problems` directory is never followed.
- **Every tool description and the server instructions stay within 2,000 characters as sent**,
  shared suffixes included, pinned by `McpToolCatalogTest` and `McpInstructionsTest`. As sent:
  instructions 1,973; `get_problem` 1,983; `repair_steps` 1,983; `stats` 1,788; `slow_passes`
  1,356; `review_queue` 1,084; `submissions` 804; `list_problems` 691. The instructions put the
  hand-over — the server names nothing, deciding is the reader's job — ahead of the lists, so a
  future overrun cuts what matters least.
- **`returned` is deferred** to its own plan.

## Rationale

A step is a recorded fact: every field on it is stored or computed by a stated rule, which keeps
the server on the counting side of the line the counts-and-names-nothing ADR drew. Naming every absence — `noDiff`, an absent `verdict`
beside `outcome`, `codeLate`, `casesComplete: false`, `truncated` — is
[[concepts/assumption-vs-measurement]] applied to a new surface: a reader never has to guess
whether a gap is a measurement. `casesComplete` was added in review for exactly that reason — the
counts of a partly observed grading read as the full set, which `TestcaseSummary`'s own invariant
forbids.

Unresolved failures start steps because the measurement says they are 25 of 65, and they are the
evidence the design was written for. The calculator takes the whole problem because the late rule
compares with the problem's next record in any language, and a late side keeps an identical-code
step because there "identical" is the signature of the race, not of nothing having changed.
`fromCodeLate` exists because review found the doubt on the wrong item: a late run is marked on
itself, but the diff that used its code sits on the next run, whose reader never saw the doubt.

The default limit, not truncating `errorText`, is the size control: `errorText` is the step's core
evidence and the diff already stops at 400 lines. Argument errors and our faults go to different
readers — a tool error is handed to the model to correct, and one raised by our own invariant would
send it correcting arguments that were fine.

The code-read bound took three rounds because each check had a blind spot the next one found: a
lexical check cannot see a link, and a real-path check that resolves `problems` itself carries the
bound to wherever that link leads. Resolving only the root (it may sit behind a link, as macOS's
`/var` does) and appending `problems` by name keeps the bound where the writer puts files. The
tests make every link readable first and then assert the reader refuses it, so each case is shown
to be a real exposure, not an imagined one.

The budget: a text the client cuts loses its tail without a word, and the tail was where the
readings and the shared `incompleteHistory` warning sat. Forty-eight characters under the cap leave
room for a client that counts a little differently and need no setting on the reader's side.

`returned` (D13): the frames live outside the record repository behind `RawSessionLog`; the table
is double-encoded inside `returned_rows` (fixture `sql-run.jsonl`), so reading it is protocol
parsing on the read path and needs a new parse-side fact type; it is Programmers' data needing a
truncation rule; and no step of 4.4's prompt depends on it.

## Accepted costs

- **Submit code is never checked for lateness** — nothing records when it was fetched.
- **The ~0.3 s race is still invisible**: `codeLate` catches late attachments only. In the other
  direction, a run whose `ts` ties with the record after it is reported late — a conservative false
  positive, never a missed one.
- **Historical steps mostly have no diff.** Runs have code only from tracker versions of
  2026-10-07 on, and every measured candidate step involves a run.
- **`errorText` is returned whole**; the limit is the only bound on a `repair_steps` answer.
- **`get_problem(include=runs)` has no limit.** Measured in the quality review on 90-line Java code
  with one or two lines edited per run: ~4.6 KB per run — 10 runs ≈ 53K characters, 20 ≈ 106K,
  50 ≈ 260K (464K when a third of the code is rewritten each run). Claude Code warns at 10k tokens,
  caps at 25k by default and writes a successful result over 50,000 characters to a file, so a Java
  problem past ~10 runs will not arrive inline. Today's records (at most 17 SQL runs on a problem)
  answer in 30–35K. `repair_steps(lessonId)` is bounded but no substitute: it carries only
  fail→next pairs, and diffs rather than code. Revisit when the first file-diverted answer appears
  or a Java problem passes 10 runs.
- **A `problems` directory linked elsewhere on purpose yields no kept code.** The bound cannot tell
  a deliberate link from a planted one.
- **27 characters are left in the instructions.** Part 4.4 cannot put its readings there; the
  `exam_prep` prompt must carry its own guidance — prompts are a separate capability.
- **The budget is paid in prose.** Descriptions and instructions say less than they did; what a
  test pins was kept, the long forms were not.
- **Without `lessonId`, every problem's code is read before filtering** (deferred, M5), and
  `RecordLayout.existingDirectoryOf` lists `problems/` once per problem — O(P²): 676 listings at 26
  problems, ~475k at the full 689-problem catalog. Not needed at today's scale; if it is, skip
  problems that cannot yield a step before reading code and cache the listing per call.
- **A `runsBeforePass` of 0 is ambiguous** for a pass recorded before runs were captured, or in a
  history with `incompleteHistory`. The `stats` description says so; the answer cannot.
- **No submits-before-pass count.** Step 1 of 4.4 asks where passing took the most submits;
  `count / attempted` approximates it, and `count` includes submits after the pass.
- **`returned` is not served**: a failed SQL run's returned table stays readable only in `.ps/raw/`.

## Outcome

**Verified live 2026-10-07** — the one-correction case — after rebuilding from main `193179d` (PR #357):

- `tools/list` answers seven tools; the descriptions arrive at the as-built lengths (`get_problem`
  1,983, `repair_steps` 1,983, `stats` 1,788 …), all within the 2,000 budget.
- `repair_steps(lessonId=59036)` answers one step: the 10:51:49 WRONG run → the 10:51:51 PASS run,
  with the diff of exactly the edit made (`SELECT ANIMAL_ID FROM ANIMAL_INS` → `… WHERE
  INTAKE_CONDITION = 'Sick'`); the following PASS → PASS pair is correctly no step.
- `repair_steps` with no arguments answers 20 of 65 with `truncated: true` in 12 KB; 17 of the 20
  say `codeUnknown` (runs from before run code was kept), one each `fromCodeUnknown` and
  `toCodeUnknown`, one carries a diff — the history before 2026-10-07 is mostly diff-less, as the
  accepted costs say.
- `get_problem(59036, include=["code","runs"])` puts the code on the submit, and on the first run
  of 2026-10-07 a `diffFromPrevGrading` against the 2026-10-03 submit (the previous grading in the
  language); an older run without kept code carries neither key.
- `stats(groupBy=part)`: SELECT — 34 submits over 23 problems attempted, 23 passed, 21 on the
  first submit, median 1.0 runs before a pass.

#353, in 22 commits on `feat/353-repair-steps-over-mcp`, reviewed for spec compliance and quality
in rounds. The design's `codeUncertain` became `codeFetchedAt` on the run line and `codeLate`
on a step; the older ADR carries a note.

Found in review and filed rather than fixed on this branch:

- **#354** — the shipped statement reader follows links out of `problems/`; a linked
  `statement.md` returned the push token in an isolated copy. Its direction: this branch's bound
  as a shared helper, applied to the run-log reader too. Done in
  [[decisions/2026-10-07-no-reader-follows-a-link-out-of-problems]], whose audit found three more
  readers that feed what git publishes, the worst of them the statement inlined into the problem
  page.
- **#355** — an internal fault answers with `id: null` and HTTP 500 in both eras, where JSON-RPC
  wants the request id echoed and the read-slice ADR keeps handshake-era failures on 200
  (pre-existing; this branch pins the current shape in `McpControllerTest`).
- **#356** — `incompleteHistory` is appended after the payload, so a client that cuts a large
  answer cuts the warning first.

**The plan's acceptance case** (plan Task 12) asks more than the live data above holds: one problem solved with at least two failing runs that
change the code, then a pass; `repair_steps(lessonId=…)` shows one step per correction, each with
a diff and none `codeLate`; `get_problem(include=["code","runs"])` carries every run's code and all
but the first `diffFromPrevGrading`; `stats(groupBy="part")` carries `attempted` and `passed`. The live check above covers **one** correction (lesson 59036 has a single failing run); the
two-correction case is pinned by `RecordQueryTest` and `McpToolInvokerTest` and is confirmed live on
the first solve that has two failing runs.
