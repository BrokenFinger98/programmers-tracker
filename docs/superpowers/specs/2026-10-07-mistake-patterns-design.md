# Mistake Patterns from Repair Steps

Written: 2026-10-07
Issue: #346
Status: approved by the owner; 4.1 implemented (#348, #350), 4.2 (#352), 4.3 (#353, live check pending); 4.4 (#364, live check pending)

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

1. **#343 — record identity is `(ts, captureKey)`.** No new field and no migration. The
   `codePending` correction is `record.copy(codePath, codePending, diffFromPrev)` in
   `CodeAttachment`, so it carries the original's `ts` and `captureKey` by construction, and the
   write time is taken inside the single writer section, so two gradings cannot share it.
   `RecordHistory` resolves *newest line per `(ts, captureKey)`* instead of per `captureKey`.
   Measured on the live log 2026-10-07: 242 lines in 121 `(ts, captureKey)` groups, every group one
   grading plus its correction (two groups differ only by a field a later version added to the
   correction line). Where a reader needs one string — `runs.jsonl`, `repair_steps` — the identity
   is written `recordId = "<ts>#<captureKey>"`. Needs its own ADR, amending
   [[decisions/2026-08-05-code-pending-correction-append]].
2. **A wrong database submit is WRONG.** The submit path sends `msg: "실패"` per failing case.
   Fixture from 273711 `attempts/001.raw.jsonl`, scrubbed. Protocol §7 gains the string.
3. **A database run MySQL rejects is COMPILE_ERROR.** The query is refused before it runs — the
   same stage as a failed compile. Shape: `passed: false`, no `returned_rows`, `msg` an error tuple
   `(<code>, '<text>')`. Fixtures from the two frames of 2026-10-03. Protocol §6 gains the shape.
   The error text stays in the record as the failing case's `msg` — it arrives on the `finish`
   frame, not on an `error` frame, so it never becomes `errorText` (served as a step's
   `failedMessage`; as built in #350).

### 4.2 Every run keeps its code

`problems/<id>/runs.jsonl`, one line per run, appended when the run's code is attached (the
moment `Solution.<ext>` is written today):

```json
{"recordId":"2026-10-03T16:57:23+09:00#<captureKey>","language":"mysql",
 "codeFetchedAt":"2026-10-03T16:57:23.4+09:00","code":"select …"}
```

Exactly four keys. **Verdicts and messages are not in the line**: `log/submissions.jsonl` is their
only authority (a copy would disagree once a classification rule changes, as #350 just did), and
readers join it by `recordId` (`<ts>#<captureKey>`). The write is idempotent by `recordId`,
checking complete lines only, so a crash-torn line cannot block the retry.

- **Full code, not a diff.** Code is small (an SQL run ~200 B; fifty Java runs ~150 KB) and a
  full copy makes every line readable on its own. Diffs are computed on read.
- `Solution.<ext>` and `attempts/NNN.*` are unchanged; existing readers keep working.
- **Committed**, riding the next submit's commit as run log lines already do — no extra commits.
  The owner accepted that every run's code is published with the records (2026-10-07). This
  narrows the `.gitignore` rationale in the record template, which kept run *frames* out because
  they "bury the solving history in its own scratch work"; code lines in one file per problem do
  not, and the template comment is updated to say so.
- Programmers' returned tables (SQL) stay in `.ps/raw/` and are **not** copied here — they are
  Programmers' data. MCP may read them from there; deferred from 4.3 to its own plan.
- Not written for gradings recorded before this ships. Past history has no run code and the
  readers say so (`code` absent), never guess it.

**Measured (2026-10-07 10:29 KST, lesson 59035).** The owner alternated two queries as fast as
possible, four runs: the code landed 0.53 / 0.28 / 0.25 / 0.24 s after each record and every run
got its own query (WRONG, PASS, WRONG, PASS); the fastest human gap was 1.6 s.
`ChannelCapture` handles frames in order and awaits the attachment, so a `start` pressed during
the fetch is not visible at attach time.

**`codeFetchedAt`.** The code is fetched from the problem page after the grading is recorded, so a
run attached late — the startup retry after an expired session or rate limit — gets whatever the
page holds then, possibly a later run's. Comparing `codeFetchedAt` with the next record's `ts`
(when that grading finished being recorded, not when it started) catches a late attachment. Its
limit: it can miss a second Run pressed within the ~0.3 s fetch window and finishing after the
fetch. If the start of a grading is ever needed, the `start` frame's arrival in `.ps/raw/` is the
place.

### 4.3 Repair steps over MCP

**`RepairSteps`** — a pure calculator (development-rules §3). Input: one problem's gradings in time
order — every language, paired per language — each with its code where known. Code comes from
joining `runs.jsonl` by `recordId`, and a submit's from its `attempts/` file; a run's code is marked
`codeLate` when its `codeFetchedAt` is later than the `ts` of the problem's next record, in any
language. Output: one step per grading that did not pass and is followed by another grading of the
same problem in the same language:

```json
{"lessonId":273711,"title":"…","part":"SELECT","level":2,"language":"mysql",
 "from":{"recordId":"…","ts":"…","action":"run","outcome":"JUDGED","verdict":"COMPILE_ERROR",
         "failedMessage":"(1054, \"Unknown column 'ii1.rarity' in 'where clause'\")",
         "failedCases":1,"totalCases":1},
 "to":  {"recordId":"…","ts":"…","action":"run","outcome":"JUDGED","verdict":"WRONG"},
 "diff":"--- a/from\n+++ b/to\n@@ …"}
```

- A step whose code is unknown on either side is returned **without** `diff` and says why in
  `noDiff`; it is never dropped and never paired across the gap.
- Consecutive identical code (compared on read) does not make a step — nothing was corrected —
  unless a side's code was attached late, where identical code is the race's signature rather than
  a sign that nothing changed.
- The final step in a language ends at its passing grading when there is one.

**MCP surface**

| Tool | Change |
|---|---|
| `repair_steps` (new) | `since`, `language`, `part`, `lessonId`, `limit` (20 when not given). Newest first. The pre-exam call |
| `get_problem` | `include`: `code` (each submit's code), `runs` (each run's code and its diff from the grading before it, on the existing items), `returned` (for failed database runs, the returned table read from `.ps/raw/`, truncated — **deferred to its own plan**). Default response unchanged |
| `stats` | `groupBy` gains `part` and `level`. Each `part`/`level` bucket adds `attempted`, `passed`, `passedFirstSubmit` and `runsBeforePass` (median) beside the existing count — counting problems, not (problem, language) pairs |

All of these count or return stored facts. None ranks problems or names a weakness.

**As built** (#353; plan `docs/superpowers/plans/2026-10-07-repair-steps-over-mcp.md`, ADR
[[decisions/2026-10-07-repair-steps-are-served-not-judged]]):

- Two calculators: `CodeTimeline` attaches each grading's code and marks it late, `RepairSteps`
  pairs per language and diffs. They take one problem's whole timeline, because the late check
  compares with the problem's next record in any language. `codeLate: true` marks a side; a late
  side keeps a step even when its code matches (`noDiff: "sameCode"`). Code is compared with
  trailing newlines trimmed. The LCS diff moved from the store into `domain/calc`, so attempt
  diffs and steps share one implementation.
- "Failed" is "did not pass", unresolved verdicts included — 25 of 65 candidate steps on the live
  log, measured 2026-10-07 (131 gradings; none of the 65 between two submits). Such a `from` has
  no `verdict` and carries `outcome`. `from` also carries `failedMessage`, the first failing case's
  own message: a rejected SQL query's MySQL error is there, not in `errorText`, which only an
  `error` frame fills (4.1 item 3 expected `errorText`; #350 left the tuple in the case's `msg`).
- A step without a diff says why in `noDiff` (`fromCodeUnknown` · `toCodeUnknown` · `codeUnknown` ·
  `sameCode` · `tooLarge`). Submit code is never marked late: nothing records when it was fetched.
  A diff cut at 400 lines carries `diffTruncated: true`, and the failing side's case counts carry
  `casesComplete: false` when some cases never arrived.
- `repair_steps` returns 20 steps unless `limit` says otherwise, with `count`, `total`, and
  `truncated: true` when the list was cut. A refused argument is a tool error in the filter's own
  words; an invariant breaking behind valid arguments is an internal error, never advice.
- `get_problem(include)` adds code to the existing items (`code`; and for runs `codeFetchedAt`,
  `codeLate`, `diffFromPrevGrading`/`noDiff`, and `fromCodeLate` when the earlier side was late).
  Without `include` the answer is byte-identical to before; the log is read once either way.
  `include=runs` has no limit. **`returned` is deferred** to its own plan.
- The `stats` problem counts appear on `part` and `level` buckets only and count problems, each in
  the bucket of its newest known part or level; `count` stays the number of submits.
- Kept submit code is read only from a regular file whose real path lies under the records' real
  `problems/` directory; a `problems` directory that is itself a link is not followed.
- Claude Code cuts server instructions and each tool description at 2,048 characters, so every one
  stays within 2,000 as sent; the instructions are at 1,973. **4.4 must carry its guidance in the
  `exam_prep` prompt itself**, not in the instructions — and its step 5 should date run code by the
  tracker versions that keep it (2026-10-07 on), since submit code was always kept.

### 4.4 The `exam_prep` prompt

Before this part the server exposed tools only. It gains the MCP **prompts** capability with one
prompt, `exam_prep(language?, since?, part?)`. A prompt is instructions the client hands its model —
it is where the interpretation the server must not do is *asked for*, in the open:

1. `stats(groupBy=part)` and `(groupBy=level)` — where passing took the most runs and submits.
2. `repair_steps(...)` — cluster the steps into recurring patterns. Name each pattern by what the
   diffs show, cite the record ids behind it, and say how many problems it spans. A pattern seen
   once is not a pattern.
3. For each pattern: problems to re-solve (from the steps' lessons), and untouched problems in the
   same part from `list_problems(status=untouched, part=…)`.
4. For each pattern: two or three short drills aimed at the exact point — e.g. call `substring`
   for the 3rd–4th characters.
5. Readings that are easy to get wrong: runs are not attempts, absent is not zero, run code is
   kept by tracker versions from 2026-10-07 on. **The prompt itself must carry these** — the
   server `instructions` are at 1,973 of the 2,000 characters a client receives whole (#353).

In Claude Code a prompt appears as a slash command, so the pre-exam session is one command.

## 5. Not in this design

- Persisting diagnoses (option B).
- Any server-side classification of mistakes beyond verdicts.
- Timing for SQL in `slow_passes`.
- A `review_queue` horizon — useful, unrelated, its own issue.

## 6. Testing and acceptance

| Part | Tests | Live acceptance |
|---|---|---|
| 4.1 | Fixtures from the measured frames; resolver, mapper, assembler (three layers); `RecordHistory` keeps two byte-identical gradings with distinct `ts`; a correction still supersedes its original; old logs resolve unchanged | `get_problem 131537` answers 3 submits and 10 runs |
| 4.2 | Writer appends a four-key line per run; idempotent by `recordId` on complete lines, torn line healed; `codeFetchedAt` from the injected Clock | One real problem solved with several runs → `runs.jsonl` has one line per run, committed with the submit |
| 4.3 | `RepairSteps` with zero mocks: failed→passed, failed→failed, unknown code on either side, repeated identical runs, run→submit across actions; `CodeTimeline` late rule (any language, never for submits); `RepairStepFilter`; `FileGradingCodes` torn line, path escape and links out of `problems/`; `get_problem(include)` and its unchanged default; `stats` part/level; every tool description and the instructions within 2,000 characters as sent | `repair_steps(lessonId=…)` on that problem shows every correction |
| 4.4 | `prompts/list` and `prompts/get` in both protocol eras | `/exam_prep` in Claude Code produces patterns citing record ids |

## 7. Accepted costs

- **Diagnoses are recomputed and may be named differently each time.** Option A's price.
- **The records grow** by every run's code. Measured scale makes it small; it is not free.
- **Run code is published with the records.** The owner's decision; it reverses part of a
  template comment written for raw frames.
- **Runs recorded before this shipped have no code**, so the first pre-exam sessions see diffs
  mostly for what was solved after it. Submit code was always kept, so a step between two submits
  has a diff whenever it happened — but none of the 65 candidate steps measured on 2026-10-07 was
  one.
- **A late attachment gets the page's newest code**, possibly a later run's. The server marks such
  a side `codeLate: true` (its `codeFetchedAt` is later than the `ts` of the problem's next
  record); a second Run pressed within the ~0.3 s fetch window can slip past that check, and
  submit code, which records no fetch time, is never checked.
- The costs the 4.3 build added — `include=runs` unbounded, the code-read bound, the instructions
  budget — are in its ADR (4.3, As built).
