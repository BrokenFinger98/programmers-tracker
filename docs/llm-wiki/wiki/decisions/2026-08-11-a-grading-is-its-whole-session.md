---
type: decision
project: programmers-tracker
tags: [capture, dedup, protocol, records, measurement]
author: BrokenFinger98
created: 2026-08-11
updated: 2026-08-11
sources: [decisions/2026-08-05-capture-pipeline-stages, decisions/2026-08-05-write-serialization, concepts/assumption-vs-measurement, raw/sessions/2026-08-11-capture-defects-found-by-solving.md]
---

# A grading is its whole session, not the frame that ended it

## Context

The capture key is the writer's only dedup handle. Programmers issues no submission id
(protocol doc §11) and `(lessonId, action, attempt)` is not unique, because a run keeps the
previous submit's attempt number. The key had to be derivable identically in a different
process, so that replaying a stored raw session drops the record its live capture already
wrote ([[decisions/2026-08-05-capture-pipeline-stages]]).

It was derived from the **terminal frame**: `digest(lessonId, action, lastFrame)`.

## What that turned out to mean

Measured 2026-08-11 on lesson 181947. The owner solved the problem, ran it four times and
submitted once. The submit passed. It was dropped:

```
Dropped a replayed capture of lesson 181947 — already recorded
```

Its key was `7f0beaa55092fc63` — the key of a submit recorded on **6 August with verdict
WRONG**. Because an algorithm submit terminates on `finish`, and a measured `finish` is:

```json
{"identifier":"…lesson_id\":181947…","message":{"action":"submit","type":"finish"}}
```

byte-identical across the two gradings. It carries no verdict, no score, no timing, no
identity of its own. **So the submit key was a constant per problem, and every submit after
the first collided with it.**

Runs were hit one step less badly: the `result` frame differs by pass/fail but not between
two gradings with the same outcome, so both of that day's failing runs collided with a
failing run from five days earlier.

The record repository had been saying so all along — every problem in it had **exactly one**
submit. That reads like someone who passes first try. It was the bug, and this is a tool whose
stated purpose is that "of 449 attempts, only the 43 successes are knowable".

## Options considered

1. **Add the session id to the key.** Unique per capture and trivially stable across a
   restart. Rejected: two subscriptions receiving one broadcast open two sessions, and the
   dedup exists precisely to collapse those.
2. **Add a timestamp.** Rejected outright — a replay must derive the same key, and a clock
   cannot.
3. **Key on the parsed verdict and testcases** rather than on frames. Rejected: the key would
   then move whenever parsing changed, and every stored record's key would silently stop
   matching its own frames.
4. **Digest every frame of the session.** Chosen.

## Decision

`CaptureKey.of(lessonId, action, frames)` digests **every frame the assembler accepted**, in
arrival order, verbatim.

- **A replay derives the same key**, because the raw log keeps every frame verbatim and both
  paths accept exactly the same subset — a line that yields no facts is skipped live and on
  replay alike. Frames are trimmed before digesting, because the live path holds the text as
  it arrived and the replay path reads it back without its line break.
- **Two real gradings differ**, because the testcase frames carry `run_time` and
  `memory_size`. Verified against all nine captures on disk: nine distinct keys, no
  collisions, including two passing runs five seconds apart.

## Accepted costs

- **Two gradings whose every frame is byte-identical still collide.** Timings make that
  vanishingly unlikely for an algorithm problem and it is not impossible — and **SQL is where
  it is plausible**, because SQL sends no per-case timing at all (protocol doc §6). Two
  identical SQL submissions of the same query could still key alike. Stated rather than
  engineered around: the alternative is a key a replay cannot reproduce, which breaks the
  property the key exists for.
- **Every stored key is now historical.** Records written before this derive from the old
  basis, so they will never match a newly computed key. Harmless — old raw sessions are
  discarded or set aside, not on the work list — but it means the log holds keys of two
  different kinds, and nothing marks which.
- **The key costs more to compute**, proportional to the session rather than constant. A
  grading is a handful of kilobytes; this is not a real cost, and it is worth saying it was
  weighed rather than ignored.

## Outcome

The four captures the defect stranded — two failing runs, a passing run and the passing
submit — were still in `.ps/raw/` and still on the reconciler's work list, because the writer
drops the *record* and not the capture. They were recovered by a restart.

The regression is built from the two measured `finish` frames rather than from a hand-written
pair (dev rules §6.2), and it fails if the basis is narrowed back to the last frame.

The lesson is not about frames. **The record repository was showing the symptom for five
days** — exactly one submit per problem — and it read as a fact about the owner rather than as
a defect. [[concepts/assumption-vs-measurement]] is usually invoked about placeholders in the
data; this was the same error made about a pattern *in* the data.

### The first accepted cost came due the same day (#159)

⚠️ (old) — "Two identical SQL submissions of the same query could still key alike. Stated
rather than engineered around."

It was not hypothetical for a day. The same SQL query submitted twice produced byte-identical
frames, and the second submission was dropped.

The resolution did not touch the key. It corrected **where the key may be consulted**:
`RecordWriter.write` is the live path and records unconditionally, `RecordWriter.replay` is
the reconciler's path and is the only one that dedups. Byte-equality is evidence that two
*stored* sessions are one grading; it is not evidence about two things that arrived separately
down the socket. The cost above is therefore retired for live capture and still stands, by
design, for replay — which is the only place it was ever load-bearing.

A test had asserted the discarded reading in so many words (`consume` the same fixture twice,
expect one record). See [[concepts/tests-that-explain-defects]].
