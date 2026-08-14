---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [coroutines, observation-socket, dependencies, testing]
created: 2026-08-14
updated: 2026-08-14
sources: [raw/sessions/2026-08-14-the-warnings-and-what-was-under-them.md]
---

# A preview API is acceptable where a test pins what it means

`kotlinx.coroutines.flow.timeout` stays, marked `@OptIn(FlowPreview::class)` on the narrowest
scope that needs it. The acceptance rests on the two tests that pin its meaning — not on the
judgement that preview APIs are usually fine.

## Context

`CableChannelSubscriber.collectOnce` applies `Flow.timeout(silenceDeadline)` to the observation
socket. That call is the **only** thing that notices a channel has gone quiet, and
[[decisions/2026-08-05-backend-stack]] makes this the one long-lived outbound stream in the
system. #94 is the issue that moved the deadline *above* the heartbeat filter, because the
heartbeat is the only traffic an idle channel produces and a deadline below the filter fired on
every quiet channel.

The API is `@FlowPreview`, so the compiler warned on every build. The warning had been there for
weeks, invisible under a second one until #309 removed it.

`@OptIn` is not a suppression. It is the documented way to answer *"yes, we accept that this can
break"*, which makes it a decision rather than a line of housekeeping.

## Options considered

**A. Annotate and accept.** One annotation on `collectOnce`.

**B. Replace with `withTimeoutOrNull`.** Stable API, no warning.

**C. Hand-write a per-emission timer.** Stable, and semantically what we want.

## Decision

**A.**

## Rationale

**B is not the same thing.** `withTimeoutOrNull` bounds the *whole collection*; `Flow.timeout`
bounds the *gap between emissions*. Substituting it would end a healthy socket the first time a
channel stayed open past the deadline — a subscription that dies while the problem page is still
open, which is exactly the failure the deadline exists to detect. It is not a drop-in and calling
it one would have shipped the bug.

**C is more code in the worst place to have more code.** A hand-rolled inter-emission timer in
the path whose failure mode is *a silently dead subscription* — the constitution's worst outcome,
since a channel nobody is watching records nothing and says nothing.

**What makes A affordable is not the annotation. It is that both failure modes are already
caught:**

| How it could break | What catches it |
|---|---|
| The declaration is renamed or removed on a coroutines upgrade | **Compile failure.** Loud, immediate, impossible to miss |
| The *meaning* changes silently — gap-between-emissions → total-collection-time | `heartbeats hold the socket open and never reach the capture` runs a 200 ms deadline against a flow emitting for 600 ms and asserts **zero** reconnects. A total-time reading fires and the test goes red |
| The deadline stops firing at all | `silence beyond the deadline ends the attempt and reconnects` — a never-emitting flow must reconnect |

The second row is the one that matters. A silent semantics change is the only way a preview API
hurts you without telling you, and it is the row that would be empty if the test did not exist.
**Had it been empty, the answer here would have been C.**

## Accepted costs

- A coroutines upgrade can fail the build on this line. That is a bounded, visible cost paid at
  upgrade time, by whoever is doing the upgrade, with the tests right there.
- The acceptance is only as good as the two tests named above. **Deleting or weakening
  `heartbeats hold the socket open and never reach the capture` silently revokes the basis for
  this decision** — the KDoc on `collectOnce` says so at the call site, which is where somebody
  about to delete it will be.
- One `@OptIn` in the codebase invites the next one to be added by habit. The rule this ADR sets
  is that each one repeats the table above and answers it, or it does not go in.

## Outcome

`./gradlew compileKotlin --rerun-tasks` emits **zero** warnings — the first time in the project's
history. That is the real deliverable: the next warning to appear is one nobody has read yet,
which is only useful if the count is zero. #309 and #310 exist because a warning nobody reads is
how the one that matters gets buried, and both of them had been buried.
