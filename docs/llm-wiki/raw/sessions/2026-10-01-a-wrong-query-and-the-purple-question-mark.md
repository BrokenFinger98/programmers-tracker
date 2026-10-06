# 2026-10-01 — a wrong query and the purple question mark

Raw session record. Immutable (wiki schema §1). 16:34–16:48 KST; recovered from the inbox snapshot
of session `b240e44e` (the precompact copy taken 2026-10-06), sliced by KST. Continues
`2026-09-30-the-sensor-hands-the-session-over.md`.

---

## The question

The owner sent a screenshot of the sensor badge: a purple `?` on an SQL problem, tooltip
`run UNKNOWN 0/1`, and asked what it meant.

The purple `?` is the state the sensor reserves for *the server saw a grading it could not
classify*. `get_problem 131118` showed three runs that afternoon (15:55, 15:56, 16:33), each
`UNKNOWN`, no submit.

## What the frames said

The raw session for the latest run (`.ps/raw/recorded/…-131118.jsonl`) held `start` and `finish`
only. The database `run` path reports its single result on the `finish` frame itself
(protocol §6): `passed: false`, a `returned_rows` table — and **`msg: null`**.

`VerdictResolver` classifies a failure by its message string alone, and a failure that matches no
measured string stays `UNKNOWN` by design ([[decisions/2026-08-05-failure-taxonomy]]). A passing
database run resolves from `passed: true`; a *failing* one had never been measured — the only
existing fixture, `sql-run.jsonl`, is a passing run. So every wrong SQL run was going to raise the
alarm reserved for the unrecordable, and three false alarms in an afternoon teach the owner to
ignore it.

The query itself: the owner asked whether their fix was right; the remaining fault was an address
prefix, not the tool. The submit at 16:39:11 was recorded `PASS 1/1`.

## The fix (#341 → PR #342)

Options weighed and the reasons are in the ADR
`2026-10-01-a-failed-run-that-returned-a-result-is-wrong`. In short: not a blanket "null message
means WRONG" (the submit path has no measured null-message counterexample, and the rule exists for
the day one appears), not teaching the resolver the problem kind, but carrying the one fact the
frame has — **whether the case returned a result of its own**.

- Tests first: a scrubbed fixture `sql-run-wrong.jsonl` cut from the 16:33 frame; resolver tests
  for both branches; a mapper test; an assembler test through the fixture (`RUN`, `JUDGED`,
  `WRONG`). Red on the missing field.
- `TestcaseResult.returnedResult` (nullable — absent on paths that never report one and on records
  written before it), set by the mapper from `returned_rows`; `verdictOf` reads a null message as
  WRONG when a result was returned and as unknown otherwise.
- ktlint failed once on argument wrapping in the mapper; fixed. Gates and guards passed.
- Protocol §6 and §15 #17 record the failing frame.
- Container rebuilt from the branch at 16:43, CI 7/7 green at 16:47, squash-merged (main
  `46e80d0`), container rebuilt from main at 16:48:32 (image id unchanged across the two builds).

Left open on purpose: the three `UNKNOWN` records from the afternoon stay as written (the log is
append-only). The live check — a wrong SQL run showing `✓ run WRONG 0/1` — was handed to the
owner for the next time they solved one.
