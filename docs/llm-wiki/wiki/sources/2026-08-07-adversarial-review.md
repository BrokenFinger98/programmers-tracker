---
type: source
project: programmers-tracker
tags: [adversarial-review, security, capture, runners, failed-attempts]
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-07-adversarial-review.md]
---

# 2026-08-07 session summary — seven runners, then four critics

Back-filled 2026-08-11.

## Key claims

1. The owner chose **server-generated runners** over letting an MCP client write them, and
   accepted the maintenance cost explicitly; language order was set by measured usage.
2. "Supported" is earned only by an execution suite running the generated artifact against a
   real toolchain — with a CI step that fails if that suite was skipped.
3. Four independent reviewers found four CRITICAL, and each defect had a passing test beside it:
   the ping never reset the silence deadline (an idle channel reconnected every ~15 s forever);
   a grading whose `start` was missed was dropped before the raw log; the raw file was moved out
   of the recovery queue before the record was appended; `detectRepository` accepted an
   *enclosing* repository and would have committed the user's unrelated work.
4. **Protocol-supplied example values were injected as executable code** into runners the user
   runs — demonstrated by a payload that created a directory under `node` while the runner
   printed a plain `FAIL`. Fixed at the parser, not the emitters.
5. Every runner fix moved the same way: refuse rather than guess, and say why. `AMBIGUOUS` is
   an honest answer where a classification would have been a guess.
6. critic-pipeline's verdict named the pattern four days before it was extracted: *each has a
   test that walks past the defect without asserting on it*.
7. `/watch` still answers `started` whether or not the subscription works, and the rejection
   log discards the reason string. Open.

## Pages this source updated

[[concepts/tests-that-explain-defects]] · [[decisions/2026-08-07-server-generated-runners]] ·
[[decisions/2026-08-07-heartbeat-behind-the-lock]] · [[decisions/2026-08-08-run-raw-sessions]] ·
[[concepts/actioncable-broadcast-observation]]
