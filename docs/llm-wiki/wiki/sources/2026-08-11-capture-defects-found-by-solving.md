---
type: source
project: programmers-tracker
tags: [capture, protocol, testing, measurement, tooling, documentation]
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-11-capture-defects-found-by-solving.md]
---

# 2026-08-10~11 capture-defect session summary

## Key claims

1. Nine capture-path defects were fixed; **five were invisible to the test suite** and
   surfaced only by writing, running and submitting code in a browser against the real judge.
2. An algorithm submit's `finish` frame carries no verdict, score or timing and is
   byte-identical across gradings of one problem — so a key derived from the terminal frame
   was a constant per problem, and every submit after the first collided with it.
3. A failing run emits **one `error` frame per diagnostic** and then a `result`; a
   cached-result submit emits its `error` and then grades in full. `error` terminates nothing.
4. Each ActionCable subscription receives the broadcast with **its own identifier** stamped
   in, so two channels open on one problem produce two byte-different copies of one grading.
5. SQL sends no per-case timing, so two identical SQL submissions are byte-identical —
   byte-equality is evidence about two *stored* sessions, never about two live arrivals.
6. Compile-error classification was matching javac's `:\d+: error:` shape only; Python's
   `SyntaxError:` was landing as RUNTIME_ERROR. Measured for java and python3, nothing else.
7. **Tests and fixtures had encoded three of the defects as facts** — a KDoc citing a
   protocol section that does not contain its claim, a fixture README stating our own
   truncation as a Programmers behaviour, and an assertion spelling out the discard rule.
8. The record repository had shown the symptom for five days — exactly one submit per
   problem — and it read as a fact about the owner rather than as a defect.
9. `raw/sessions/` had been empty since 2026-08-05 while `log.md` recorded 33 ingests, and
   two ADRs cited raw sessions that were never written. The visible half of the ritual
   survived; the substance did not.
10. The English-only guard had never executed its search on Linux (#123), and state was
    resolving against the working directory rather than the record repository (#126).

## Pages this source updated

[[decisions/2026-08-11-a-grading-is-its-whole-session]] ·
[[decisions/2026-08-11-a-failing-run-ends-at-its-result]] ·
[[decisions/2026-08-11-korean-for-the-user-facing-half]] ·
[[decisions/2026-08-10-guards-must-prove-they-ran]] ·
[[decisions/2026-08-10-state-beside-the-records]] ·
[[decisions/2026-08-10-scheduling-is-not-diagnosis]] ·
[[decisions/2026-08-10-sensor-observations]] ·
[[decisions/2026-08-08-run-raw-sessions]] ·
[[concepts/tests-that-explain-defects]] ·
[[concepts/assumption-vs-measurement]]
