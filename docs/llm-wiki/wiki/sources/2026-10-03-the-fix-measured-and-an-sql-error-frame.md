---
type: source
project: programmers-tracker
tags: [verdict, measurement, sql]
created: 2026-10-06
updated: 2026-10-06
sources: [raw/sessions/2026-10-03-the-fix-measured-and-an-sql-error-frame.md]
---

# 2026-10-03 session summary — the fix measured, and an SQL-error frame

## Key claims

1. **#341 verified live**: lesson 131537, run at 15:23:52 recorded `JUDGED · WRONG` with
   `returnedResult: true`.
2. **An SQL-error run, measured for the first time**: `passed: false`, no `returned_rows`, and a
   `msg` that is MySQL's own error tuple — `(1054, …)`, `(1222, …)`. It matches no measured
   verdict string and stays `UNKNOWN`, as the 10-01 ADR said it would until measured. Classifying
   it as a compile error was proposed, not decided; not yet in the protocol document.
3. **The raw frame answered what the record could not**: a run keeps no code of its own, but its
   `finish` frame kept the returned table, which showed the wrong and passing runs differed only in
   row order.
4. The count the tool gave that afternoon was folded — see [[sources/2026-10-06-the-history-that-folded]].

## Pages this source updated

[[decisions/2026-10-01-a-failed-run-that-returned-a-result-is-wrong]] · [[concepts/assumption-vs-measurement]]
