# Mistake Patterns from Repair Steps

Written: 2026-10-07
Issue: #346
Status: approved by the owner; not implemented

---

## 1. The question

The owner wants the records to answer one thing the current tools cannot:
**what do I keep getting wrong?** The answer they have in mind is not a problem type, it is a
habit — argument order of a method they call often, a method name they mix up with another
language's, a syntax slip that recurs. Before an exam they want those habits gathered and drilled:
the problems where the habit showed, and the specific point inside them that went wrong.

Two properties of that answer shape everything below.

- **It is a diagnosis about the learner.** By [[decisions/2026-08-10-scheduling-is-not-diagnosis]]
  and [[decisions/2026-08-12-the-server-counts-and-names-nothing]] that is the AI's job, not the
  server's. The server must hand over facts good enough for the AI to make it.
- **Its evidence is the correction, not the failure.** A failed grading says *that* something was
  wrong. What was wrong shows in what changed before the next attempt: two arguments swapping
  places, `lenght()` becoming `length()`, a column gaining its table alias. Argument-order mistakes
  often compile and produce no error text at all — the diff is the only witness.

## 2. What the tools can say today — measured 2026-10-07

Every MCP tool was called against the live records (30 submits, 28 passes, almost all SQL).

| Finding | Consequence for this design |
|---|---|
| Runs keep no code. `Solution.<ext>` is overwritten on every run; `attempts/NNN.*` exists for submits only | The corrections that carry the evidence are lost. Lesson 273711: 11 runs, 4 rejected by MySQL for column names, 6 wrong results from reading the parent/child relation backwards — and none of their code survives |
| `get_problem` returns `codePath` and `diffFromPrev` (submit to submit), never the code | A client without filesystem access cannot see any code |
| A wrong SQL **submit** carries `msg: "실패"` and resolves `UNKNOWN` (273711 attempt 1, 0/6) | The two verdict-less entries in `stats` are this. Wrong submits are invisible to anything that counts WRONG |
| A **run** MySQL rejects carries its error tuple, e.g. `(1054, "Unknown column …")`, and resolves `UNKNOWN` | Measured 2026-10-03; recorded in the 2026-10-01 ADR's outcome |
| `get_problem 131537`: 1 submit and 5 runs; the log holds 3 and 10 (#343) | Every reader folds byte-identical gradings — exactly the repeated wrong runs this design depends on |
| Every SQL problem carries the single tag `implementation` | The tag axis says nothing for SQL; Programmers' `part` (SELECT, GROUP BY, JOIN, …) is the axis that does |
| `slow_passes` excludes 23 SQL passes as untimed | Out of scope here; SQL sends no per-case timing |

## 3. Options for where the diagnosis lives

| | Shape | For | Against |
|---|---|---|---|
| **A. Diagnose on demand** (chosen) | The server returns repair steps; the AI clusters them into patterns in the session; nothing it concludes is stored | MCP stays read-only ([[decisions/2026-08-06-mcp-read-slice]]); smallest build; every diagnosis sees all current data | A pattern's name can vary between sessions; a trend over months is recomputed each time |
| B. Store the AI's notes | A separate annotation store the AI may write: label, note, evidence record ids | Stable labels; "is this habit fading?" becomes a count | First exception to the read-only property; a prompt-injected AI can fill it with noise (records themselves stay safe) |
| C. Server-side rules | Classify compiler messages by pattern | Fast | The rule-based analyzer the constitution forbids; blind to mistakes that compile |

**A, decided with the owner on 2026-10-07.** One learner's records hold hundreds of repair steps
after months, not millions — small enough to read in one pre-exam session. If counting a habit's
decline becomes a real need, B can be added with its own ADR; everything A stores is what B
would read.

## 4. Design

Four parts, in build order. Each is its own issue and PR.

### 4.1 Records that are right (prerequisite)

A repair step paired from a folded or mislabelled history is wrong in a way no reader can see.

1. **#343 — record identity.** Every record gets a `recordId` when it is written (a
   per-grading value, not derived from bytes). A correction line names the `recordId` it
   supersedes. Lines written before this have no `recordId`; their identity is `(ts, captureKey)`.
   Measured on the live log 2026-10-07: 242 lines in 121 `(ts, captureKey)` groups, every group one
   grading plus its `codePending` correction (two groups differ only by a field a later version
   added to the correction line). Needs its own ADR, amending
   [[decisions/2026-08-05-code-pending-correction-append]].
2. **A wrong database submit is WRONG.** The submit path sends `msg: "실패"` per failing case.
   Fixture from 273711 `attempts/001.raw.jsonl`, scrubbed. Protocol §7 gains the string.
3. **A database run MySQL rejects is COMPILE_ERROR.** The query is refused before it runs — the
   same stage as a failed compile. Shape: `passed: false`, no `returned_rows`, `msg` an error tuple
   `(<code>, '<text>')`. Fixtures from the two frames of 2026-10-03. Protocol §6 gains the shape.
   The error text stays in the record as `errorText`, as compile output does.

### 4.2 Every run keeps its code

`problems/<id>/runs.jsonl`, one line per run, appended when the run's code is attached (the
moment `Solution.<ext>` is written today):

```json
{"recordId":"…","ts":"2026-10-03T16:57:23+09:00","language":"mysql",
 "verdict":"WRONG","errorText":null,"code":"select …"}
{"recordId":"…","ts":"2026-10-03T16:58:39+09:00","language":"mysql",
 "verdict":"WRONG","errorText":null,"sameAsPrevious":true}
```

- **Full code, not a diff.** Code is small (an SQL run ~200 B; fifty Java runs ~150 KB) and a
  full copy makes every line readable on its own. Diffs are computed on read.
- `sameAsPrevious` when the code equals the previous run's — pressing Run twice is common.
- `Solution.<ext>` and `attempts/NNN.*` are unchanged; existing readers keep working.
- **Committed**, riding the next submit's commit as run log lines already do — no extra commits.
  The owner accepted that every run's code is published with the records (2026-10-07). This
  narrows the `.gitignore` rationale in the record template, which kept run *frames* out because
  they "bury the solving history in its own scratch work"; code lines in one file per problem do
  not, and the template comment is updated to say so.
- Programmers' returned tables (SQL) stay in `.ps/raw/` and are **not** copied here — they are
  Programmers' data. MCP may read them from there (4.3).
- Not written for gradings recorded before this ships. Past history has no run code and the
  readers say so (`code` absent), never guess it.

**Open risk — measure first.** Programmers saves the code on `run`, with no time-based autosave
(protocol §15.1), and the tracker fetches it after the grading settles. If the next Run is pressed
before that fetch lands, the fetched code is the next run's. Runs 3–4 seconds apart were recorded
on 2026-10-03. The first task of 4.2 measures the fetch delay against the gap to the next grading;
a run whose fetch completed after the next grading on the same problem began is written with
`"codeUncertain": true` rather than silently attributed.

### 4.3 Repair steps over MCP

**`RepairSteps`** — a pure calculator (development-rules §3). Input: one problem's gradings in one
language, in time order, each with its code where known. Output: one step per failed grading that
is followed by another attempt at the same problem:

```json
{"lessonId":273711,"title":"…","language":"mysql","part":"SELECT","level":2,
 "from":{"recordId":"…","ts":"…","action":"run","verdict":"COMPILE_ERROR",
         "errorText":"(1054, \"Unknown column 'ii1.rarity' in 'where clause'\")",
         "failedCases":1,"totalCases":1},
 "to":  {"recordId":"…","ts":"…","action":"run","verdict":"WRONG"},
 "diff":"--- a\n+++ b\n@@ …"}
```

- A step whose code is unknown on either side is returned **without** `diff` and says so; it is
  never dropped and never paired across the gap.
- Consecutive identical code (`sameAsPrevious`) does not make a step — nothing was corrected.
- The final step of a problem ends at the passing grading when there is one.

**MCP surface**

| Tool | Change |
|---|---|
| `repair_steps` (new) | `since`, `language`, `part`, `lessonId`, `limit`. Newest first. The pre-exam call |
| `get_problem` | `include`: `code` (each submit's code), `runs` (the run timeline with code and per-run diff), `returned` (for failed database runs, the returned table read from `.ps/raw/`, truncated). Default response unchanged |
| `stats` | `groupBy` gains `part` and `level`. Each bucket adds `attempted`, `passed`, `passedFirstSubmit` and `runsBeforePass` (median) beside the existing count |

All of these count or return stored facts. None ranks problems or names a weakness.

### 4.4 The `exam_prep` prompt

The server today exposes tools only. It gains the MCP **prompts** capability with one prompt,
`exam_prep(language?, since?, part?)`. A prompt is instructions the client hands its model — it is
where the interpretation the server must not do is *asked for*, in the open:

1. `stats(groupBy=part)` and `(groupBy=level)` — where passing took the most runs and submits.
2. `repair_steps(...)` — cluster the steps into recurring patterns. Name each pattern by what the
   diffs show, cite the record ids behind it, and say how many problems it spans. A pattern seen
   once is not a pattern.
3. For each pattern: problems to re-solve (from the steps' lessons), and untouched problems in the
   same part from `list_problems(status=untouched, part=…)`.
4. For each pattern: two or three short drills aimed at the exact point — e.g. call `substring`
   for the 3rd–4th characters.
5. Readings that are easy to get wrong, from the server's `instructions`: runs are not attempts,
   absent is not zero, records before 2026-10 have no run code.

In Claude Code a prompt appears as a slash command, so the pre-exam session is one command.

## 5. Not in this design

- Persisting diagnoses (option B).
- Any server-side classification of mistakes beyond verdicts.
- Timing for SQL in `slow_passes`.
- A `review_queue` horizon — useful, unrelated, its own issue.

## 6. Testing and acceptance

| Part | Tests | Live acceptance |
|---|---|---|
| 4.1 | Fixtures from the measured frames; resolver, mapper, assembler (three layers); `RecordHistory` keeps two byte-identical gradings with distinct `recordId`s; old logs resolve unchanged | `get_problem 131537` answers 3 submits and 10 runs |
| 4.2 | Writer appends a line per run; `sameAsPrevious`; `codeUncertain` when a later grading started before the fetch | One real problem solved with several runs → `runs.jsonl` has one line per run, committed with the submit |
| 4.3 | `RepairSteps` with zero mocks: failed→passed, failed→failed, unknown code on either side, repeated identical runs, run→submit across actions | `repair_steps(lessonId=…)` on that problem shows every correction |
| 4.4 | `prompts/list` and `prompts/get` in both protocol eras | `/exam_prep` in Claude Code produces patterns citing record ids |

## 7. Accepted costs

- **Diagnoses are recomputed and may be named differently each time.** Option A's price.
- **The records grow** by every run's code. Measured scale makes it small; it is not free.
- **Run code is published with the records.** The owner's decision; it reverses part of a
  template comment written for raw frames.
- **History before this ships has no run code**, so the first pre-exam sessions see only what was
  solved after it.
- **A run's code can be attributed to the wrong run** when runs are seconds apart; 4.2 detects
  and marks it rather than preventing it.
