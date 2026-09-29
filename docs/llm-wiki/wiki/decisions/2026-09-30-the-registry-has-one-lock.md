---
type: decision
project: programmers-tracker
tags: [concurrency, sensor, measurement]
author: BrokenFinger98
created: 2026-09-30
updated: 2026-09-30
sources: [decisions/2026-09-29-a-replaced-credential-reopens-the-observation, decisions/2026-08-05-failure-taxonomy]
---

# The registry has one lock

## Context

`SubscriptionRegistry` was written "for one writer (the `/watch` handler) plus concurrent readers",
over a `ConcurrentHashMap` with immutable entries replaced wholesale. That was true when it was
written. `/watch` is now one `runBlocking` per request on virtual threads, so there are as many
writers as open tabs, and the compound operations — an admission is get → size → remove → put, a
pin is get → put — are not atomic over a concurrent map. The adversarial pass on #331 named it;
a test then reproduced it on the first run: sixteen concurrent admissions into a capacity of four
left **five** entries, and a heartbeat refresh racing a pin can write an unpinned copy over the
pin — an evictable channel mid-grading, which is the loss protocol §11 is about.

## Options considered

1. **Keep the concurrent map, make each compound operation a single `compute`.** Possible for a
   pin; an admission also consults size and evicts another key, which one `compute` cannot do.
2. **One lock around every operation over a plain map.** Eight entries, a few operations a
   second, nothing suspends inside: the lock costs nothing measurable.
3. **Serialise `/watch` itself.** Wider than the defect, and the same tabs' heartbeats are
   independent everywhere else.

## Decision

Option 2. `watched` is a `HashMap` and every public method — `watch`, `markActive`,
`markSettled`, `isGrading`, `unwatch`, `snapshot` — runs under one lock. The KDoc says so, in
place of the sentence that had gone false.

## Rationale

The invariants — never more than `capacity` entries, a pin never lost — are what the registry is
for, and they were held by an assumption that stopped being true when the handler changed. A lock
holds them by construction. Two tests keep it honest: sixteen concurrent admissions into four
slots, twenty rounds; and four pinners racing eight hundred refreshes, ten rounds.

## Accepted costs

- One lock on every heartbeat's path. Microseconds against a thirty-second cadence.
- `snapshot()` copies under the lock. It is a diagnostics view of at most eight entries.

## Outcome

Issue #333. The admission test failed on the old code every run (`expected 4, was 5`) and passes
on the new one; the full suite is green.
