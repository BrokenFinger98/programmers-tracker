---
type: source
project: programmers-tracker
tags: [capture, ci, testing, measurement, autonomy]
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-05-capture-pipeline-built-end-to-end.md]
---

# 2026-08-05 afternoon–night session summary

Back-filled 2026-08-11. Covers #14–#40, after the morning's design review
([[sources/2026-08-05-design-review-and-stack-upgrade]]).

## Key claims

1. The owner demanded adversarial review of the whole communication inventory before
   implementation, using their own worked example (does the write path need a queue?) as the
   standard — that review is what shaped the capture pipeline.
2. Run testcases identify themselves by 0-based `index`, not `testcaseId`; without a fixture
   the mapper declined them and a run would have settled `UNKNOWN`.
3. `run` saves the code — established by a second trial that removed the action and kept the
   time, which killed the debounced-autosave confound and deleted design §4.4's main-world
   editor injection.
4. GitHub's Windows runners check out with `core.autocrlf=true`, so a fixture's captured
   newlines became CRLF and the capture stopped saying what the server sent.
5. A Kotlin test with a non-`Unit` expression body is silently not run by JUnit — eight reached
   `main` through green three-OS CI, including the cookie-masking test.
6. A guard verified against a dirty workspace verifies nothing: the never-ran-test check passed
   locally on stale result files and failed in CI on all three OSes.
7. Autonomous issue→PR→CI→merge authority was granted this night, with guardrails; every
   session through 2026-08-11 runs under it.

## Pages this source updated

[[concepts/assumption-vs-measurement]] · [[decisions/2026-08-05-capture-pipeline-stages]] ·
[[decisions/2026-08-05-write-serialization]] · [[decisions/2026-08-05-hexagonal-architecture]]
