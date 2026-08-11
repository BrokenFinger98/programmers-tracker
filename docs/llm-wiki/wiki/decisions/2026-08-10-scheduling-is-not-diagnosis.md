---
type: decision
project: programmers-tracker
tags: [analysis, mcp, review-queue, boundaries, spaced-repetition]
author: BrokenFinger98
created: 2026-08-10
updated: 2026-08-11
sources: [decisions/2026-08-06-mcp-read-slice, decisions/2026-08-10-sensor-observations, concepts/assumption-vs-measurement, raw/sessions/2026-08-11-capture-defects-found-by-solving.md]
---

# The server may schedule; it may not diagnose

## Context

Design §6.4 asks for a review queue: `confidence = f(attempts, hint level, elapsed time,
performance)` and `next review = last pass + g(confidence)`. It is the first feature that
turns records into "what should I do next" — everything shipped before it collects.

CLAUDE.md's forbidden list says the opposite in one line:

> ❌ **Rule-based analyzers inside the server** — interpretation is the AI's job. The server
> collects and aggregates, no further.

A confidence heuristic is rule-based by construction. The two documents were written four
days apart and neither mentions the other.

## Options considered

1. **Do not build it; let the AI schedule from `submissions`.** Honest to the prohibition and
   the cheapest thing to do. Rejected: every client would re-derive the same arithmetic from
   raw records, each differently, and the one thing a *record* server can offer over a chat
   log — a stable answer to "when did I last hold this, and how well" — would never exist.
   The prohibition was written against diagnosis, and applying it here reads the letter
   against the reason.
2. **Compute the date and return only the date.** What §6.4 literally describes. Rejected:
   this is the shape the prohibition is actually about. A bare `dueAt` is an assertion the
   reader cannot check, and when the numbers behind it are chosen rather than measured — they
   are, see below — an unarguable assertion is the worst possible packaging for them.
3. **Compute the date and ship the inputs with it.** Chosen.

## Decision

The boundary is **diagnosis versus scheduling**, and it is worth stating in the terms that
make it decidable rather than as a preference:

- **Diagnosis is a claim about the learner** — "you are weak at DFS/BFS". The wiki already
  records that exact sentence as a misdiagnosis drawn from `partTitle` alone. It stays the
  AI's job.
- **Scheduling is a claim about a date**, derived from recorded facts by a published formula.
  It says nothing about the person that the records do not already say.

The server may do the second, on two conditions that are not negotiable and are what make the
first option unnecessary:

**Every item carries the facts that produced it.** `attempts`, `sawQuestions`, `focusedSec`,
`passedAt` and the band all travel together, so a reader that disagrees with the schedule can
see why it said what it said. A tool that returned `dueAt` alone would be the analyzer the
constitution forbids; one that shows its inputs is a calculator whose work is visible.

**The formula is written down where a reader will find it** — in `mcp.md`, in the tool's own
description, and here.

Two deliberate omissions follow from the same reasoning:

- **`focusedSec` is reported and never scored.** Calibrating it needs a per-level distribution
  of how long problems actually take. There are four recorded problems. A level-blind
  threshold would call 45 minutes on a Lv3 a struggle and 45 minutes on a Lv0 normal using the
  same number, which is inventing a measurement rather than making one.
- **Performance versus expectation is not in the formula.** `acceptanceRate` correction is
  design §6.6, a feature of its own; folding half of it in here would leave two places
  computing the same idea differently.

**Absence never buys confidence.** A pass recorded with no extension watching cannot reach the
longest interval. Reading "we were not watching" as "no help was taken" is the single error
direction that pushes a shaky problem two months out, and it is the same principle
[[decisions/2026-08-10-sensor-observations]] settled for the field itself: absent is not zero.

## Rationale

The prohibition exists because a wrong diagnosis is confidently wrong and unfalsifiable to its
reader. That property is what this decision removes rather than argues around: an item whose
inputs are visible can be contradicted by anyone holding it.

Calibration is the honest weak point, and there is exactly one source for it — design §6.4
states two worked examples, "1st try, no hints, 10 min → 60 days" and "5 wrong tries, level-3
hints → 3 days". The formula reproduces both, and both are pinned as test cases. That is not
evidence the numbers are right; it is evidence they are the numbers this project asked for.

## Accepted costs

- **The intervals are chosen, not measured.** 60 / 21 / 7 / 3 days, and the penalty weights
  behind them, come from two examples in a design document. No retention data exists for this
  learner or any other, and none will until the queue has been used for months. The ADR says
  so, the tool description says so, and `mcp.md` says so — because a number that travels
  without its provenance acquires one.
- **A band is still a judgment.** Four bands claim less than a decimal would, but "this pass
  was shaky" is an interpretation whatever its resolution. The mitigation is disclosure, not
  denial.
- **The prohibition is now narrower than its text.** Someone reading CLAUDE.md alone will find
  a rule this feature appears to break. That is a cost of not amending the constitution in the
  same change, and the reason this ADR is linked from the tool, the code and `mcp.md`.
- **Hint level remains approximated.** `sawQuestions` says a tab was reachable and opened, not
  that help was taken. The design asked for a level; we have a boolean, and it is the only
  measured thing in the neighbourhood.

## Outcome

`review_queue` ships as the fifth MCP tool. `ReviewQueue` is a pure calculator in
`domain/calc/` — a snapshot and an instant in, items out — so a live query and a re-analysis
of old records cannot drift apart (dev rules §3).

One implementation detail earned its own test rather than a comment: **due-ness is decided in
the offset the pass was recorded in, not the server's.** The container runs UTC, measured on
the running image; a learner in Seoul is nine hours ahead of it, so a global timezone setting
would have been a second place for the day boundary to be wrong. The record already carries
the only offset that means anything.

**Applied a second time, 2026-08-10 (#134).** `slow_passes` hit the same wall from the other
side. Design §6.5 asks for "markedly slower than same-tag, same-level problems", which needs
peers a two-pass record set does not have, and the boundary answered it without a fresh
argument: the server ranks and reports, the caller decides where the line falls. The whole
distribution comes back in one call, so the ordering *is* the comparison. Nothing invented a
threshold, and the tool takes one instead.

The absence rule transferred intact and mattered more here. A pass with no timing — every SQL
pass, since the protocol sends no per-case time at all, and any case lost to a timeout — is
excluded from the ranking and **counted in the answer**. Sorting a missing reading as zero
would have put the problems we know least about at the fast end of a list whose entire subject
is speed. Same shape as `sawQuestions`: absent is not zero, and the reader has to be able to
see how much was absent.

Two features now share one boundary rather than each having their own, which is the outcome
this ADR was written to make possible.
