---
type: decision
project: programmers-tracker
tags: [data-model, sensor, records, analysis]
author: BrokenFinger98
created: 2026-08-10
updated: 2026-08-11
sources: [raw/sessions/2026-08-11-capture-defects-found-by-solving.md, raw/sessions/2026-08-10-sensor-verified.md]
---

# What the sensor records beyond "this problem is open"

## Context

Design §6.4 defines the review queue's input as
`confidence = f(attempt count, hint level, elapsed time, performance)`. Two of the four do
not work:

- **hint level** is a dead field. `hintLevel` appears exactly once in `src/main` — its own
  declaration, defaulted to 0 — because the design assumed hints would come from an AI
  through a `mark_hint` tool that was never built.
- **elapsed time** is wall-clock since the problem was first announced (§5.2). Open a
  problem, eat dinner, come back: three hours on an easy problem.

The owner asked, while the sensor extension was being written, to collect more than the bare
minimum since later features might want it. That is normally a YAGNI violation. It is not
here, for one specific class of data: **things observable only in the browser, only while
solving, and impossible to backfill.** Everything else the server can fetch later.

## Options considered

**A. Collect nothing beyond the identifiers.** Smallest sensor, and it is what design §8
sketched. But the two broken confidence inputs stay broken, and every problem solved
meanwhile is unrecoverable.

**B. Collect what the browser can see, broadly** — scroll depth, keystroke timing, focus
events, statement expansion. Rejected: no consumer, and each one widens what an extension
reads off a page holding a learner's work.

**C. Collect exactly the two with an identified consumer in §6.4.**

Measured on live pages 2026-08-10 while deciding:

- The **질문하기 tab** (`/lessons/<id>/questions`) opens on problems **not yet solved**, and
  its first post is 문제 풀이 공유합니다. Opening it while stuck is seeking help.
- The **다른 사람의 풀이 tab** answers **401 until the problem is solved** — verified: 120803
  unsolved → 401, 120804 and 181951 solved → open. It cannot answer "how much help did you
  need" and would have been a field that is always false. Measuring first is what stopped a
  second dead field being added next to `hintLevel`.
- **Time to first keystroke** was in an earlier sketch and is dropped: no consumer in §6.4,
  so YAGNI applies.

## Decision

**C.** The record gains one nullable `sensor` object carrying `focusedSec` and
`sawQuestions`. `/watch` accepts both as optional, leniently. `ProblemTimer` keeps the
latest reading per problem beside the start time it already holds.

Grouped rather than flattened because the provenance differs: everything else in a record
came off the wire from Programmers or was computed from it, and this came from a browser
extension that may not be installed. A reader weighing the two should see which is which.

## Rationale

- Both inputs §6.4 names and does not have, from the only place they exist.
- Absent stays distinguishable from zero, so a record written without an extension does not
  claim the learner spent no time — the assumption-versus-measurement rule
  ([[concepts/assumption-vs-measurement]]) applied to a field whose absence is routine.
- Cumulative rather than incremental, so a lost heartbeat costs nothing: the next one
  carries the whole answer.
- The reading is refused for a problem with no timer. The clock starts when a problem is
  *announced*, and letting telemetry start one would put a measured-looking elapsed time on
  a problem nothing is watching.

## Accepted costs

- **A schema change**, the second in three days. Design §5.2 is amended in the same branch.
- **The timers document changed shape**, from `{lesson: epochSecond}` to
  `{lesson: {startedAt, …}}`. Both are read and only the new one is written, because live
  machines have the old form — the developer's had five clocks running. Untested migration
  code would have reset them, and a wrong `elapsedSec` is worse than an absent one.
- **`focusedSec` is only as good as the extension.** It counts nothing before the extension
  is loaded, nothing in a browser profile without it, and nothing while the tab is behind
  another window even if the learner is reading a printout. It is a better number than
  wall-clock, not a true one.
- **`sawQuestions` says a tab was opened, not that help was taken.** It cannot distinguish
  reading one comment from copying a solution, and it must never be presented as if it can.

## Outcome

Shipped 2026-08-10 with #120, the day the extension was first verified in a browser.

**`hintLevel` was removed rather than wired (#136).** This ADR left the dead field open —
"removed or wired when the review queue lands" — and the review queue landed reading
`sensor.sawQuestions` and never it, which is what made the field provably dead rather than
merely unused. It turned out to be worse than unused: `hintLevel: 0` was going out over MCP
on every submission, so an AI asked "does this learner lean on hints" had every reason to
answer "no, zero across the board" from a number nobody ever measured. That is the failure
[[concepts/assumption-vs-measurement]] names, live on the wire.

The trade this ADR accepted is now paid in full and worth restating: a boolean that was
measured replaced a level that never was. Design §6's hint-dependence trend is a coarser
thing than it was drawn as, and it is the first version of it that is true.

Related: [[decisions/2026-08-08-run-raw-sessions]] · [[decisions/2026-08-04-passive-broadcast-observation]].
