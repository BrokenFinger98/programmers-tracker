---
type: source
project: programmers-tracker
tags: [measurement, failed-attempts, mcp, records, ci, security]
created: 2026-08-14
updated: 2026-08-14
sources: [raw/sessions/2026-08-14-the-night-the-records-learned-the-question.md]
---

# 2026-08-14 session summary — the records learn the question, and three of my claims are refuted

Eleven PRs, and the thing worth carrying forward is not any of them: **not one defect was found by
reading code**, and **three findings contradicted something I had asserted within the hour**.

## Key claims

1. **A status that disagrees with what happened is the same defect in both directions.** #167 was a
   green badge over a lost submission; #269 was `fatal:` printed over a push that succeeded. The
   credential pointer had been written into the record repository's own `.git/config` with a
   container path, and that file is the host's working copy —
   [[decisions/2026-08-13-the-pointer-is-passed-not-persisted]].
2. **A guard that reads a name it remembers will eventually read the wrong thing.** The coverage
   gate asked for `domain/calc` by exact name and never saw the 862 branches in
   `domain/calc/runner`. It reads every package the report contains now —
   [[decisions/2026-08-13-a-floor-per-package-and-a-reason-per-exception]].
3. **An exemption that hides its number is one nobody can retire.** The same gate filtered exempt
   packages out before reporting, so the one package whose exemption promised follow-up was the
   one nobody could check on (#283).
4. **The statement rides on a fetch already made, and lives in a file written once**, because the
   README beside it is regenerated on every grading — the trap that blocked design §6.9, one file
   over. [[decisions/2026-08-13-the-statement-travels-with-the-record]].
5. **Storing data for an AI is not the same as serving it.** `get_problem` did not return the
   statement for two hours, during which the sentence that justified capturing it stayed exactly
   as true (#278/#279). The same shape appeared once more that night: `docs/mcp.md` warns a human
   about every way these records mislead, and the model never opens it (#286).

## Three refutations, which is the point

⚠️ **`equals`/`hashCode` was a guess.** `SubmissionRecord`'s 70 uncovered branches: 69 are in
`<init>`, because Kotlin compiles each default parameter into a bitmask test and the class has 15
defaulted fields. Same conclusion, wrong mechanism — and the mechanism decides how to filter it.
Stated in an issue before it was measured.

⚠️ **The runners' 66% was not depressed by a skipping test.** I expected CI to read higher than
local, where 8 C# execution tests skip for a broken dotnet. CI's uploaded artifacts: 49 execution
tests, 0 skipped, and `domain/calc/runner` reads 575/862 on **both**. Execution proofs add zero
branch coverage — the generation branches were already covered, and executing generated code
proves it correct without visiting a new path.

⚠️ **The runners were never missing an execution proof at all.** I had recommended building one as
the next piece of work. Seven have existed since #84/#86, with the exact `assumeTrue` posture I was
about to propose. The ADR that repeated my premise was corrected (#285) rather than left to send
the next reader after work already done.

The common shape: each was an inference stated with the confidence of a measurement. See
[[concepts/assumption-vs-measurement]].

## What found each thing

The host's own git · a `grep` with no filters · `find` over the record repository · CI's uploaded
test artifacts · method-level coverage counters · the owner reading a rendered page · the five real
problem pages.

Zero found by re-reading the code that produced them.
