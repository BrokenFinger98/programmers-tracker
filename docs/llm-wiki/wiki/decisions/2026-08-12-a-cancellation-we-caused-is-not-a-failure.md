---
type: decision
project: programmers-tracker
tags: [coroutines, subscription, logging, near-miss]
author: BrokenFinger98
created: 2026-08-12
updated: 2026-08-12
sources: [raw/sessions/2026-08-12-clean-slate-verification.md]
---

# A Cancellation We Caused Is Not a Failure — and the Type Cannot Tell You

## Context

`CableChannelSubscriber.unsubscribe` cancels the observation job. That happens on **every
language switch and every closed tab** — the ordinary path
[[decisions/2026-08-11-a-pass-belongs-to-its-language]] exists for.

`collectOnce` wrapped the collection in `runCatching`, which catches `CancellationException`
like any other throwable. One swallowed exception cost three things, all measured across seven
switches during the clean-slate sweep (#217):

1. a WARN per switch — *"anything broadcast meanwhile is lost"* — about a stop we asked for,
   in the same log as the reconnect warnings that mean something
2. an `UNREACHABLE` written back into the health map **after** `unsubscribe` removed it,
   leaving an entry for a channel nobody watches
3. one more pass of the retry loop, which calls `connectionLost()` — settling any grading still
   in flight as INCOMPLETE and logging it as *dropped mid-grading*

The third is the one with teeth. The first is the one that gets noticed, and it is the same
shape as #215 one day earlier: **a warning that fires on an ordinary action is a warning that
gets trained away.**

## Options considered

**A — Filter the log message.** Suppress the WARN when the cause is a cancellation. Rejected:
it treats the symptom. The health entry and the retry pass would remain, and a log filter is a
lie told to the reader rather than a fix.

**B — Rethrow on type: `if (it is CancellationException) throw it`.** The textbook fix, and the
first one written here.

**C — Rethrow only when the job itself was cancelled.**

## Decision

**C.** `if (it is CancellationException && !currentCoroutineContext().isActive) throw it`

## Rationale

**B is wrong here, and the existing suite proved it within a minute.**

`Flow.timeout()` reports the silence deadline by throwing `TimeoutCancellationException`, which
is a `CancellationException`. Rethrowing on type alone sends the deadline straight out through
the retry loop — **disabling the reconnect this class exists for.** A socket was measured
closing silently after ~30 minutes with no exception and no close frame (protocol §11); treating
silence as failure is the whole design.

`silence beyond the deadline ends the attempt and reconnects` went red the moment B was applied.
That test predates this work by weeks, and it is the reason the near-miss cost one test run
rather than a month of unnoticed gaps.

What actually separates the two cases is not the exception but **who asked**. A timeout leaves
the job active; `unsubscribe` does not. `isActive` reads that directly.

## Accepted costs

- **The condition is subtler than the idiom.** `if (it is CancellationException) throw it` is
  what a reader expects, and the extra clause looks like defensive noise until you know about
  `TimeoutCancellationException`. The comment carries the reason, and comments rot — the
  mitigation is that the deadline test fails loudly if the clause is ever simplified back.
- **Two cancellation sources are now conflated by a proxy.** `isActive` answers *"was this job
  cancelled"*, not *"did we cancel it"*. If anything else ever cancels the scope — a shutdown —
  it takes the same quiet path. That is correct today (a shutdown is also not a failure) and it
  is an inference, not a measurement.
- **The health-map entry is still written on real failures for channels being torn down** in
  the narrow window before `unsubscribe` removes it. Unchanged by this, and invisible in an
  answer: `healthOf` already reports UNREACHABLE for an absent channel by design.

## Outcome

Found by reading logs during a verification run that was about something else entirely — the
clean-slate sweep (#218), where seven language switches produced seven identical warnings in a
row and the repetition is what made them visible. One-off noise stays invisible; noise with a
rhythm does not.
