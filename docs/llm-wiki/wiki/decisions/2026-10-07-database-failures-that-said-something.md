---
type: decision
project: programmers-tracker
tags: [protocol, verdict, measurement, sql]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# Database failures that said something

## Context

Two measured database failures resolved `UNKNOWN`: a wrong submit, whose cases say only `실패`
(lesson 273711, 2026-10-03), and a run MySQL rejected, whose message is the driver's error tuple
with no table (lesson 131537, 2026-10-03). The first hid every wrong SQL submit from the verdict
counts — the two verdict-less submits in `stats` were this; the second was the accepted cost of
[[decisions/2026-10-01-a-failed-run-that-returned-a-result-is-wrong]], now measured.

One thing had to be measured before either could be classified: what a submit says when MySQL
rejects the query. If it also said bare `실패`, the wrong-result rule would file syntax errors as
WRONG. The owner submitted `SELECT NO_SUCH_COLUMN FROM ANIMAL_INS` on lesson 59034 on
2026-10-07 at 09:28:19: the case said `실패 (런타임 에러)`.

## Options considered

1. **Classify both** — bare `실패` as WRONG, an error tuple on a run as COMPILE_ERROR.
2. **Classify only the run error**, leaving bare `실패` unknown — the fallback had the measurement
   shown a rejected submit saying bare `실패` too.
3. **Teach the resolver the problem kind.** Rejected for the reason the 2026-10-01 ADR gives: the
   message shape is the fact that matters, and both shapes are absent from every algorithm
   capture.

## Decision

Option 1, chosen by the measurement. Bare `실패` is matched exactly; the error tuple is matched
only on a case that reports `returnedResult == false`, which is the run `finish` it was measured
on.

## Rationale

All three shapes were captured live and are fixtures (`sql-submit-wrong.jsonl`,
`sql-run-error.jsonl`, `sql-submit-error.jsonl`). Exact and narrow matching keeps the rule
"measured only": a failure with any other parenthesised reason, or a tuple anywhere else, still
stays unknown, and a test pins each of those.

## Accepted costs

- **A rejected query is a COMPILE_ERROR on the run path only.** A submit MySQL rejects says
  `실패 (런타임 에러)` and is recorded RUNTIME_ERROR. The algorithm path corrects that from the
  preceding run's bound error text; SQL cannot, because a rejected run arrives on `finish`, not
  on an `error` frame, so nothing is bound. The same mistake can therefore be counted as two
  different verdicts depending on whether it was run or submitted. Correcting it would need the
  submit to look back at the previous run's message — not built, because no measured case needs it
  yet.
- For SQL there is no compiler; "compile error" names the stage (refused before running), and the
  MySQL text stays in the testcase's `msg`.
- Records written before this keep `UNKNOWN`; the log is append-only.

## Outcome

#349. Tests in three layers on the three fixtures; the resolver tests seen failing without the
rules. Live acceptance pending: a wrong SQL submit and a syntax-error run on the rebuilt container
show WRONG and COMPILE_ERROR.
