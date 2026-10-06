---
type: source
project: programmers-tracker
tags: [mcp, storage, measurement, failed-attempts]
created: 2026-10-06
updated: 2026-10-06
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# 2026-10-06 session summary — the history that folded

## Key claims

1. **Every reader folds distinct gradings that share a capture key** (filed #343). Lesson 131537:
   the log holds 10 runs and 3 submits; `get_problem` answers `runCount: 5`, `submissionCount: 1`.
2. Cause: `RecordHistory` resolves the log as newest line per capture key, and the key is derived
   from the grading's bytes — the #159 collision, fixed on the write path in August and still live
   on the read path.
3. The 2026-10-03 answer had reported the folded count ("four runs" where eight were recorded)
   without comparing it to the raw sessions it listed minutes later.

## Pages this source updated

[[decisions/2026-08-11-a-grading-is-its-whole-session]] ·
[[decisions/2026-08-05-code-pending-correction-append]] ·
[[concepts/assumption-vs-measurement]]
