# 2026-10-06 — the history that folded

Raw session record. Immutable (wiki schema §1). Found during the wiki ingest of 2026-10-06
(session `b240e44e`), while checking the counts in `2026-10-03-the-fix-measured-and-an-sql-error-frame.md`
against the record repository.

---

## The readers fold distinct gradings

The 10-03 answer said the problem had "4 runs and 1 submit". While writing this record the raw
directory showed more sessions than that, so the log was read directly.

`log/submissions.jsonl`, lesson 131537, that afternoon (each record appears twice: the
`codePending: true` line and its correction — by design):

| ts (KST) | action | verdict | captureKey |
|---|---|---|---|
| 15:21:02 | run | WRONG | 39e412c5… |
| 15:21:11 | run | UNKNOWN (1054) | bbdc3dde… |
| 15:21:16 | run | UNKNOWN (1222) | c491bbfc… |
| 15:21:23 | run | WRONG | 39e412c5… |
| 15:21:45 | run | WRONG | 39e412c5… |
| 15:23:39 | run | WRONG | 39e412c5… |
| 15:23:52 | run | WRONG | 39e412c5… |
| 15:25:36 | run | PASS | 08498436… |
| 15:25:45 | submit #1 | PASS | bb400541… |
| 15:53:49 | run | PASS | b6bfb538… |
| 15:53:58 | submit #2 | PASS | bb400541… |
| 15:55:54 | run | PASS | b6bfb538… |
| 15:55:57 | submit #3 | PASS | bb400541… |

`get_problem 131537` on 2026-10-06 answers `submissionCount: 1`, `runCount: 5` — attempt 3 and the
last run of each key. Ten runs and three submits were recorded; every reader sees five and one.

Cause, read from the code: `RecordHistory.of` resolves the log as *newest line per capture key*
(the rule the `codePending` correction introduced). `captureKey` is built from the grading's
bytes, and a grading with no per-case timing — all of SQL, every failed compile — is byte-identical
whenever its output is. #159 (2026-08-11) moved the dedup index off the **write** path for exactly
this reason and concluded "both runs were recorded correctly", which is true of the log. Nobody
checked the readers, which still use the key as the record's identity.

At 15:37 on 10-03, when the owner asked, the log held eight runs and one submit; the answer said
four runs, which is the folded view (15:21:11, 15:21:16, 15:23:52, 15:25:36). The assistant
reported the tool's count without checking it against the raw sessions it read minutes later for
another reason — the eight files were in the same listing. Submits 2 and 3 came after 15:53, so
the folding of submits was first visible on 2026-10-06. Filed as #343; not fixed in the ingest.
