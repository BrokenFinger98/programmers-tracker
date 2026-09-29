---
type: decision
project: programmers-tracker
tags: [sensor, courtesy, concurrency]
author: BrokenFinger98
created: 2026-09-30
updated: 2026-09-30
sources: [decisions/2026-08-11-the-session-is-checked-where-it-can-answer, decisions/2026-09-29-the-sensor-hands-over-the-session]
---

# One probe in flight

## Context

`SessionHealth` promised "asked at most once per interval": the cache is what makes an
authenticated request to Programmers on every heartbeat affordable
([[decisions/2026-08-11-the-session-is-checked-where-it-can-answer]]). The promise held for one
caller at a time. The extension posts one heartbeat per open tab and each is served on its own
thread, so on a cold cache — or the moment after `credentialReplaced()` empties it — every
heartbeat found nothing cached and every one probed. The adversarial pass on #332 measured it:
eight concurrent heartbeats, eight probes; one reset among eight heartbeats, eight more.
Development-rules §9.3 ("the same level as a browser") is what the cache exists to keep, and a
credential replacement was multiplying its cost by the number of tabs.

## Options considered

1. **A shorter window won't do it** — the burst is simultaneous, not spread.
2. **Serialise `state()` with a mutex.** Correct, but the waiters would then each *probe* in turn
   after the first one returned, unless they re-checked the cache — which is option 3 with
   extra steps.
3. **Share the probe in flight.** The first caller past an empty cache owns a `Deferred`; the
   others await it and take its answer. If the owner fails them — cancelled, or a probe that
   threw — a waiter that is still active asks for itself.

## Decision

Option 3. `inFlight` is set under the lock by the caller that decides to probe and cleared by
it, under the lock, whether the probe returned or threw; waiters await the `Deferred` outside
the lock. The generation guard from #331 is unchanged: an owner whose question predates a
replacement returns its answer to everyone who waited on it but does not cache it, and the next
caller probes with the new credential.

## Rationale

The check's cost is one request per interval by construction now, not by luck of timing, and
the property is a test: eight callers on a cold cache produce one probe. The waiters cannot be
stranded — a throwing probe is proven to release them and they ask again.

## Accepted costs

- A waiter can receive an answer produced with a credential that was replaced while it waited.
  It is the same answer the owner returns to itself, and it is not cached; the next heartbeat
  asks afresh. Thirty seconds at most.
- One more field and one more code path in a class whose point is to be boring. Both are tested.

## Outcome

Issue #334. Two tests: eight concurrent callers on an empty cache count one probe; a probe that
throws releases the callers waiting on it, and they ask again.
