---
type: source
project: programmers-tracker
tags: [sensor, guards, storage, analysis, scope]
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-10-sensor-verified.md]
---

# 2026-08-08 / 2026-08-10 session summary — the sensor proven

Back-filled 2026-08-11. 2026-08-09 has no entries; 2026-08-08 has one question, which opens the
page because it started the run-raw-sessions decision.

## Key claims

1. Run raw sessions never left `.ps/raw`, so the reconciler re-read every run ever captured on
   every boot — a growing cost with a correct output, which is why it never announced itself.
2. The owner cut design §6.3 (reactivation diagnosis, the design's own P0) in one sentence:
   only problems solved from now on matter.
3. The session cookie is fixed; **which problem, in which language** is not, and focused time
   and a questions-tab visit are things the server can never know. Hence four fields, and only
   four.
4. Measuring first killed a field before it shipped: `다른 사람의 풀이` returns 401 until the
   problem is solved, so "did you view other solutions" could only ever have recorded `false`.
5. The extension was loaded and watched to work against the live judge — the last gap between
   this tool and a user who is not its author.
6. The first attempt failed on a four-day-old container, and the rebuild flag was in a
   blockquote below the copyable command. A command handed to a user must be complete.
7. The English-only guard had **never executed its search** on Linux: `[가-힣]` is locale-
   collated (crash on `C.UTF-8`, 1006 false hits on `C`), and `|| true` made the crash look like
   a clean tree.
8. No migration code was written for the state move — no releases, forks or stars exist, and
   that was confirmed rather than assumed.

## Pages this source updated

[[decisions/2026-08-10-sensor-observations]] · [[decisions/2026-08-10-state-beside-the-records]] ·
[[decisions/2026-08-10-guards-must-prove-they-ran]] ·
[[decisions/2026-08-10-scheduling-is-not-diagnosis]] · [[decisions/2026-08-08-run-raw-sessions]]
