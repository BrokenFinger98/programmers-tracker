---
type: source
project: programmers-tracker
tags: [verdict, sensor, measurement, sql]
created: 2026-10-06
updated: 2026-10-06
sources: [raw/sessions/2026-10-01-a-wrong-query-and-the-purple-question-mark.md]
---

# 2026-10-01 session summary — a wrong query and the purple question mark

## Key claims

1. **A failing database `run` carries `passed: false`, a `returned_rows` table and `msg: null`**
   — measured on lesson 131118, three runs in one afternoon (protocol §6, §15 #17). The resolver
   classifies failures by message only, so all three were recorded `UNKNOWN` and the sensor showed
   the alarm reserved for the unrecordable.
2. **The fix carries the frame's own evidence instead of loosening the rule**: a domain field
   `returnedResult`; a null message is WRONG only when a result came back. Fixture
   `sql-run-wrong.jsonl` cut from the live frame, tests in three layers, red before green.
3. Shipped the same quarter-hour: #341 → PR #342, CI 7/7, container rebuilt from main at 16:48:32.
   The live check was left for the owner's next wrong run — it came two days later.

## Pages this source updated

[[decisions/2026-10-01-a-failed-run-that-returned-a-result-is-wrong]]
