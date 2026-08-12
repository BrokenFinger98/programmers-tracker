---
type: source
project: programmers-tracker
tags: [verification, measurement, records, browser, failed-attempts]
created: 2026-08-12
updated: 2026-08-12
sources: [raw/sessions/2026-08-12-clean-slate-verification.md]
---

# 2026-08-12 session summary — verifying against an empty repository

## Key claims

1. **Records written by five code vintages are evidence of nothing.** 49 of 57 carried a null
   `score` the current build fills, 40 a null `sincePrevSec`, three a verdict since corrected.
   A defect fixed a week ago looked identical to one still present.
2. Archived and tagged before removal, and checked first: no record was independent practice,
   and the credentials live in the project's `.ps/`, not the record repository.
3. The container must be stopped first — the attempt counter and the gap tracker are restored
   from the log at startup and held in memory.
4. **Fifteen records, one build.** Seven of seven compile failures classified, seven of seven
   passes carrying score and rating, attempt sequence monotonic across seven languages, and
   `incompleteHistory` **absent** — a field that only disappears when there are no holes, so
   #215 and the clean slate verify each other.
5. The WRONG verdict was exercised end to end for the first time, and carries
   `score: 0.0/100.0` — a score is not evidence of a pass.
6. `attempt 0` on the first record is `AttemptAuthority.NONE`, correct and only visible on an
   empty log. Checked before reporting.
7. **#217**: every language switch warns that broadcasts may have been lost, when the
   cancellation is our own. Same shape as #215, one day apart.
8. **Click coordinates resolve against the last screenshot** — the real cause of three sessions
   of flaky browser automation, after two wrong theories. Every batch must end with a screenshot.
9. Twice this session a missing record was read as "capture is broken" without looking at the
   screen. A negative observed through your own setup is not an observation.

## Pages this source updated

[[decisions/2026-08-12-a-language-is-supported-when-its-failures-are-too]] ·
[[decisions/2026-08-11-a-hole-in-the-record-is-reported-not-filled]] ·
[[decisions/2026-08-11-a-pass-belongs-to-its-language]] ·
[[concepts/tests-that-explain-defects]]
