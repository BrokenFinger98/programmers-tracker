---
type: source
project: programmers-tracker
tags: [discipline, protocol, measurement, guards, failed-attempts]
created: 2026-08-12
updated: 2026-08-12
sources: [raw/sessions/2026-08-12-the-improvement-loop-turns-inward.md]
---

# 2026-08-12 session summary — the loop turns on its own work

## Key claims

1. Guard §7, written the previous day, **refused this session's own push** for a `log.md` line
   with no raw beside it. The first person it stopped was the one who wrote it.
2. **A gate piped into anything is not a gate**: `./scripts/guards.sh | tail -2` in an `&&` chain
   takes `tail`'s exit status, so a failing guard let the push proceed. The pre-push hook checks
   the wiki gate only.
3. The session probe trusted a status code that §14 documents this API family lying with
   (200-with-HTML throttling), and the ADR claimed the endpoint "avoids" that shape — measured
   200-with-JSON and 401-with-JSON, never a throttle. Fourth *observation real, quantifier
   invented* in two days.
4. A rate limit was **deliberately not measured**: it would mean hammering Programmers to prove a
   property we can simply stop claiming. The body is shape-checked instead.
5. `score` and `rating` were parsed and dropped at `GradingFrameFacts` — 76 of 76 records null,
   while three KDocs and the object mother implied otherwise.
6. **Two defects protected each other**: `SubmissionRecord.score`'s KDoc said SQL reports no
   score (wrong — the measured fixture and §6's own example carry it), and it was uncatchable
   because the field was null for every grading anyway.
7. A test written **from the documentation** is a test of the documentation — usually a weakness,
   here the only thing in the tree able to notice.
8. Three of four defects in this stretch came from reading the project's own record of what it
   does not know, not from a failing test. A list of accepted costs turned out to be a backlog.

## Pages this source updated

[[concepts/tests-that-explain-defects]] ·
[[decisions/2026-08-11-the-session-is-checked-where-it-can-answer]] ·
[[concepts/assumption-vs-measurement]]
