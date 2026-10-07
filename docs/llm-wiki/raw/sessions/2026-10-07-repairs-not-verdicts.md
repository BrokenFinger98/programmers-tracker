# 2026-10-07 — repairs, not verdicts

Raw session record. Immutable (wiki schema §1). 08:53–16:56 KST, session `b240e44e`, sliced by KST
from the session transcript (the pre-compaction inbox snapshot, which ends at 17:25, is a prefix of
it). Continues `2026-10-06-the-history-that-folded.md`; the evening is
`2026-10-07-the-readers-that-followed-links.md`.

---

## The ask, and what the tools could not answer

The owner asked whether `get_problem` returns their code and whether runs are committed, then asked
for every MCP tool to be called and for features that find what they keep getting wrong. Read from
the code and the record repository at 08:54: `get_problem` serves the statement and `diffFromPrev`
but no code (only `codePath`), and a run keeps no code at all — `Solution.<ext>` is overwritten by
the next run and `attempts/NNN.<ext>` is written for submits only.

Every tool, called at 09:01:

| Tool | Answer | What it showed |
|---|---|---|
| `stats` | 30 submits, 28 PASS, 2 without a verdict | both were wrong SQL submits whose cases say bare `실패` — a verdict gap |
| `list_problems` | lesson 131537 at 1 attempt | 3 were recorded — #343's fold |
| `review_queue` | empty | every pass was too recent to be due |
| `slow_passes` | 23 SQL passes left out | a database grading carries no run time |
| tag axis | every SQL problem tagged `implementation` | the useful axis for SQL is the Programmers part |
| `get_problem` | statement, verdicts, submit-to-submit diffs | no code, and none ever kept for a run |

Lesson 273711 was the example: eleven runs — four rejected by MySQL over one column's name, six wrong
from reading a relation backwards — then one wrong submit. None of the runs' code existed. "Is the
last run plus a diff plus the submit enough?" No: the mistakes live in the runs between the first
failure and the pass.

## The choice: diagnosed, not stored

The owner's words (09:06, translated): confusing a method's parameter order, a method's name, a
syntax slip — the things they get wrong every time — gathered before a coding test to re-solve;
"there must be a pattern of mine; find it and give me a diagnosis that fits." Whether the record
repository is public did not matter: "it can all go up."

The evidence for that is the correction, not the failure: the diff from a failed grading to the next
attempt (an argument-order slip compiles and leaves no error text). Three options were put: (A) the
AI diagnoses on demand from repair steps the server serves, nothing stored; (B) an annotation store
the AI may write — the first exception to read-only MCP; (C) server-side classification of compiler
messages — the forbidden rule-based analyzer, blind to mistakes that compile. The owner chose A at
09:09. Two facts were checked before writing it down: a run already fetched its code (only the
storage was missing), and the server had no prompts capability.

Spec `docs/superpowers/specs/2026-10-07-mistake-patterns-design.md`, #346, PR #347 (2676bea, 09:22):
4.1 correct records, 4.2 every run's code, 4.3 repair steps over MCP, 4.4 an `exam_prep` prompt.

**A design element that did not survive the spec's self-review.** The discussion proposed a new
`recordId` field per grading for #343. The self-review measured the alternative on the live log: a
correction line repeats its original's `ts`, and 242 lines formed 121 `(ts, captureKey)` groups,
each one grading and its correction. No field, no migration.

## Process: subagents, not orchestration

The 4.1 plan offered subagent-driven execution or direct work in the session. The owner asked whether
the orchestration skill was worse. Answer at 09:28: for 4.1 it buys little. The parts are ordered,
each touches `progress.md`, the wiki index and an ADR (parallel PRs have reverted each other there
before), and each is small. The owner: "don't orchestrate; try subagents, then." From then on every
task went to a fresh implementer subagent, then a spec-compliance reviewer, then a code-quality
reviewer. Read-only reviews ran in parallel; no two implementers ever did. The largest calculator task went
to the more capable model.

Two failures of the method that day. A reviewer of an agent type that may not run builds approved
without running the tests, so the controller ran them (63 passed). And at 12:52, when the owner asked
how it was going, the implementer fixing Tasks 3–4 of 4.3 had stopped when the client session ended,
its seven fixes written and not committed. The controller ran the gates, committed `ec53a6a`, and
reverted two fixes on purpose to see the new tests fail before trusting them. Implementers were told
from then on to commit after each task.

## 4.1 — the records made right

**Measured before Part B (09:28:19).** On request the owner submitted a query naming a column that
does not exist to lesson 59034. Its case said `실패 (런타임 에러)`, not bare `실패`, so bare `실패`
could become WRONG without catching rejected queries.

| | #343 (PR #348, 4f1e660) | #349 (PR #350, f89960c) |
|---|---|---|
| Change | `RecordHistory` keys on `(ts, captureKey)` | bare `실패` → WRONG; MySQL's error tuple on a run → COMPILE_ERROR |
| Review found | a correction appended after a later identical grading; the copy contract pinned on the production path | the same rejection is COMPILE_ERROR as a run and RUNTIME_ERROR as a submit; the 09:28 frames never became a fixture; the tuple rule narrowed to `returnedResult == false` |
| Live (KST) | 09:42 — `get_problem 131537`: 1 submit / 5 runs before, 3 / 10 after, matching the log | 10:06:17 run → COMPILE_ERROR (1054); 10:06:55 submit → WRONG; lesson 59035 |

The guard stopped #349's push: four KDoc comments quoted the Korean protocol strings. They now name
the constant or the protocol section; the string literals stay.

## 4.2 — every run keeps its code

The design had promised to mark a run whose code might belong to the next run (`codeUncertain`).
Reading the capture showed that cannot be seen at attach time: frames are handled in order, so the
next `start` is not visible while the code is fetched. Measured instead (10:07–10:29, a poller on
`Solution.sql`, lesson 59035): the owner alternated two queries four times as fast as possible; each
run got its own code; the fetch landed 0.24–0.53 s after each record; the fastest human gap was
1.6 s. `codeUncertain` was dropped, and so was the design's "omit code identical to the previous
run" — a late attachment at boot reorders lines, so "previous" could name another run.

The #351 quality review raised three Important findings. A crash-torn line counted as already
written, so that run was skipped for good. A late attachment (after an expiry, at the startup retry)
gets the page's newest code, which is far more common than the 0.3 s race. Verdict fields copied into
the line would be a second authority beside the log. The line became four keys: `recordId`,
`language`, `codeFetchedAt`, `code`. PR #352 (0848dbe, 10:48). Live at 10:51 on lesson 59036: three
runs, three lines, code fetched 0.27–0.38 s after each record.

## 4.3 — repair steps over MCP

A planning agent measured the live log first (131 gradings, 65 candidate steps, 25 of them starting
at a grading with no verdict) and wrote a 12-task plan with sixteen decisions; all were accepted.
Built 11:12–16:47 in 22 code commits, 24 with the docs (#353, PR #357, 193179d). What review
changed, in order:

- **One problem in two buckets.** Part and level were read per record. A catalog that arrives after a
  problem's first records put it under both `null` and `2`, and `passedFirstSubmit` counted a first
  submit that had failed. Now each problem takes its newest known label; a test reproduced the split
  first (1 of 37 failed).
- **The code-read bound, three rounds.** Submit code was readable anywhere under the records root,
  which holds `.ps/git-credentials`, the push token. All 131 real `codePath`s lay under `problems/`, so
  the bound became `problems/` (feec25a). The implementer then showed that a link inside `problems/`
  was still followed (2cde57e: compare real paths). The re-review linked `problems` itself to `.ps`,
  and the token came back because the bound moved with the link (4980e56: resolve only the root,
  append `problems` by name). The same review reproduced the exposure in the shipped statement
  reader, which became #354.
- **Argument faults and our faults.** An invariant that broke behind valid arguments told the model
  to fix its arguments. Arguments are now read first; anything after that is HTTP 500 / `-32603`.
- **Smaller corrections.** `casesComplete: false` marks a partly observed grading, and `fromCodeLate`
  marks the diff that used a late run's code. A description claimed run capture began 2026-08-07;
  the earliest run record is 2026-08-28, so the date was removed rather than hedged. `include=runs`
  stayed unbounded, as an accepted cost.
- **The 2,048-character cut** (Tasks 9–10 quality review, 15:48). Claude Code cuts the server
  instructions and each tool description at 2,048 characters. The project's own test allowed 3,000,
  and this session's system prompt showed the instructions ending "there is no cohort here…
  [truncated]".

| Text | Before | After (budget: 2,000 as sent) |
|---|---|---|
| server instructions | 2,921 on the branch (2,607 on main since #287) | 1,973 |
| `get_problem` description | 2,548 | 1,983 |
| `repair_steps` description | 2,126 | 1,983 |
| the other four descriptions | 764–1,861 | 691–1,788 |

Filed rather than fixed: #354 (the statement reader follows links), #355 (an internal fault answers
`id: null` with HTTP 500 in both eras), #356 (`incompleteHistory` comes after the payload).

At 15:17 the owner asked whether "docs" meant every document. It meant the ones the change makes
untrue, plus their Korean twins. A grep for the tool names found `docs/bootstrap.md`, which the plan
had missed.

Live after the rebuild (16:47–16:50): seven tools listed at their as-built lengths.
`repair_steps(59036)` returned one step, the WRONG run → the PASS run, with a diff showing the single
clause added. The default call returned 20 of 65 with `truncated` in 12 KB, 17 of them `codeUnknown`.
`stats(part)` for SELECT: 34 submits over 23 problems, 21 passed on the first submit. The plan's
two-failing-runs acceptance case had no live data and stays pinned by tests. Recorded through #358,
PR #359 (f2b4ba1, 16:56).
