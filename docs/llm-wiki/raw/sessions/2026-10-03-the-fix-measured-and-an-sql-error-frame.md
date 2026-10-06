# 2026-10-03 — the fix measured, and an SQL-error frame

Raw session record. Immutable (wiki schema §1). 15:36–16:50 KST. Recovered from the inbox snapshot
of session `b240e44e`.
Continues `2026-10-01-a-wrong-query-and-the-purple-question-mark.md`.

The rest of that afternoon was SQL study driven through the MCP tools (`get_problem` on 131537,
273711, 276034) and is not recorded here; only what it showed about the tool is.

---

## The #341 fix, measured live

The owner asked `get_problem 131537` for their history. The answer included a run at
**15:23:52 recorded `JUDGED · WRONG`** with `testcases[0].returnedResult: true` — a failed SQL run
that returned a table, classified, not `UNKNOWN`. That is the live check #341 was waiting on, on
the container built from main at 2026-10-01 16:48.

## A second shape, measured for the first time

Two runs at 15:21:11 and 15:21:16 were still `UNKNOWN`. Their `finish` frames carry
`passed: false`, **no `returned_rows`**, and a `msg` that is MySQL's own error tuple:

```text
(1054, "Unknown column 'USER_ID' in 'field list'")
(1222, 'The used SELECT statements have a different number of columns')
```

The ADR of 2026-10-01 had listed exactly this as an accepted cost: an SQL-error run had been seen
on screen on 2026-09-29 but never on the wire, so whether its frame carries a message was
unmeasured. It does — the error text — and it matches no measured verdict string, so the resolver
correctly declined to guess. The assistant proposed classifying it as a compile error (the query is
rejected before it runs, the same stage as a failed compile) using the two frames as fixtures. The
owner did not take it up that day; nothing was filed.

Not yet in `docs/programmers-protocol.md` — the frames are under
`.ps/raw/recorded/20261003T062111182Z-131537.jsonl` and `…T062115911Z-131537.jsonl` in the record
repository.

## What the raw frames could answer that the record could not

The owner asked why their wrong run was wrong. A run keeps no code of its own — `Solution.sql` is
overwritten on every run — so the SQL of the wrong run is gone. But the raw `finish` frame kept the
table the query returned: the wrong run and the passing run returned the same rows in a different
order. The diagnosis (a missing or misplaced `ORDER BY` around a `UNION`) came from the preserved
original message, which is the case for keeping originals that development-rules §2.4 makes.

The answer that afternoon said "four runs and one submit"; what the log actually held is in
`2026-10-06-the-history-that-folded.md`.
