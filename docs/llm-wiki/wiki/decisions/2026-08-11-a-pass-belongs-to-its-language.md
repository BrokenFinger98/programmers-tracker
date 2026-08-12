---
type: decision
project: programmers-tracker
tags: [analysis, review-queue, storage, scope, measurement]
author: BrokenFinger98
created: 2026-08-11
updated: 2026-08-11
sources: [decisions/2026-08-10-scheduling-is-not-diagnosis, raw/sessions/2026-08-11-capture-defects-found-by-solving.md]
---

# A pass belongs to the language it was written in

## Context

The owner's case, in their words: *someone who normally solves in Kotlin and has to practise
Java because a company does not offer Kotlin.* Both languages are real practice; one of them is
the one that matters on the day.

I reported this twice as **blocked on the record-repository layout** — `attempts/001.raw.jsonl`
carries no language, so per-language attempt numbers would collide. That was wrong, and reading
the code rather than repeating the claim is what settled it:

- `attemptFile` is `attempts/NNN.<ext>` and **the extension already carries the language**;
  `001.kt` and `002.java` are different files
- attempt numbers are **per problem and monotonic across languages** by design (§5.1), so
  `001.raw.jsonl` belongs to attempt 1 whatever it was written in — the collision does not exist

The storage was never the problem. **The analysis was.** Both calculators grouped by lesson
alone:

```kotlin
val items  = history.groupBy { it.lessonId }.values                        // ReviewQueue
val passes = history.filter { it.passed() }.groupBy { it.lessonId }        // SlowPasses
    .map { (_, records) -> records.maxBy { it.ts } }
```

so a pass in Kotlin scheduled the problem as reviewed, and `slow_passes` kept **one pass per
problem, the latest** — a slow Java pass written the day after a fast Kotlin one disappeared
outright, which is the exact reading that tool exists to surface.

## Options considered

1. **Leave it; a problem is a problem.** Defensible if the record were only about algorithms.
   It is not: `slow_passes` measures a runtime, and a runtime is a property of the solution, not
   of the problem. Rejected on the measurement alone, before any argument about learning.
2. **Group by language, but carry confidence across.** A pass in Kotlin would shorten Java's
   first interval, on the reasoning that the algorithm is already known and only syntax remains.
   Rejected: how much a second language carries over **is a claim about the learner**, and
   [[decisions/2026-08-10-scheduling-is-not-diagnosis]] settled that the server does not make
   those.
3. **Change the record layout to separate languages on disk.** What I had been proposing, and
   unnecessary — see above. Rejected as a fix for a defect that is not there.
4. **Group by `(lesson, language)` in the calculators.** Chosen.

## Decision

`ReviewQueue` and `SlowPasses` key on `(lessonId, language)`. `ReviewItem` carries the language,
so two entries for one problem are legible, and the review ordering gains language as a final
tie-break so one problem yielding two items cannot reshuffle between identical calls.

Attempt counting follows the grouping: the submits that led to a Kotlin pass are not counted
against a first Java attempt. Counting them would make a first attempt in a new language look
shaky because of work that taught nothing about writing it in that language.

SQL needs no special case. `mysql` and `oracle` are separate languages, so they are separate
tracks, which is the same answer for the same reason.

## Rationale

The measurement argument is the strong one and it is not about learning at all: **a runtime
measures the solution you wrote.** Attributing a Kotlin reading to "this problem" and then
letting it displace a Java reading is not a judgement call, it is losing data.

The scheduling argument follows the boundary already drawn. "Solving it once means you can
probably write it in Java" may well be true, and it is exactly the kind of claim this server
refuses to make on the learner's behalf. Two tracks, both carrying the facts that scheduled
them, and the reader decides.

## Accepted costs

- **The queue gets longer for anyone practising two languages** — up to twice as long, and
  every item is one they have to look at. That is the point, and it will still feel like more
  work than before.
- **A genuine carry-over is ignored.** Someone who solves a problem in Kotlin and writes it in
  Java the same afternoon gets a full first-time interval for the Java pass. The alternative is
  a number nothing calibrates, which is the same trap the confidence bands already sit in.
- **`stats(groupBy=problem)` still counts by problem**, so the two surfaces now group
  differently. Correct in both cases — a count of submissions is not a schedule — but it is a
  thing a reader has to notice.
- **Old records keep their language and are unaffected**, so nothing migrates. The one machine
  with a two-language history is the author's, and it has none yet: the change is provably
  correct on tests and has never been exercised on real two-language data.

## Outcome

Seven tests, of which four exist for the direction that matters rather than the happy path:
passing in one language must leave the other's schedule untouched, a slow reading must survive
the faster language being recorded later, re-solving in the *same* language must still replace
its own reading, and two items for one problem must keep a stable order in both input orders.

`docs/mcp.md` and its Korean twin now say a problem can appear once per language, and why the
server declines to decide what a second language carries over.

## Amended 2026-08-12 (#214): the reader now has to notice one sentence, not the difference

The accepted cost above ends *"it is a thing a reader has to notice"*, and left it there.

The clean-slate sweep (#218) made it concrete for the first time: lesson 181952 has a pass in
each of seven languages, so `review_queue` holds seven items for the one bucket
`stats(groupBy=problem)` reports. Nothing is wrong with either answer, and side by side without
the explanation they read as a disagreement.

**No fourth `groupBy`.** `groupBy=language` already answers the other axis, and a
`problem_language` group would be a third way to ask what two calls answer — the speculative
kind of surface CLAUDE.md forbids. What was missing was disclosure, not a feature.

So the `stats` description says which axis it collapses and names the two tools that do not, and
a test pins it there. The description is the only place a client can learn it: `tools/list` is
read once and the results are counts (the same reasoning as
[[decisions/2026-08-11-a-hole-in-the-record-is-reported-not-filled]]'s move of prose out of the
answers).

The cost that remains is the honest one: a reader who never reads the tool description still
meets the difference unexplained. Disclosure is not the same as it not existing.
