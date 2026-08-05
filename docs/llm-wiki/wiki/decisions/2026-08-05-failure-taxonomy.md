---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [verdict, termination, reconnect, lru, failure-modes]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Failure taxonomy: termination matrix, two non-verdict outcomes, liveness from ping

Date: 2026-08-05 · Status: accepted · Issue: #8

## Context

The design terminates a capture on `finish` **or** `result_lesson_challenge` (design
§4.2) and classifies every capture into one of 5 verdicts (design §3.3). Measured
protocol facts contradict both:

- Termination differs per **(action × type)**, not per type. Algorithm submit ends at
  `finish`; SQL submit never sends `finish` and ends at `result_lesson_challenge`;
  **SQL run does send `finish`**; algorithm run ends at `result`, or at `error` on the
  error path (protocol §5–§7). The `error` type is not even in the bundle's `run`
  catalog yet was measured — the catalog is demonstrably incomplete (protocol §8).
- Back-to-back identical resubmission returns a cached result within 1 s and then
  `error` (protocol §13.2). Under the current condition that session never terminates
  and dies at the timeout, so design §9's "the server records the `error` too" is
  unreachable code.
- A memory-limit-exceeded verdict has **never been triggered**, so its `msg` string is
  unknown (protocol §14). A classifier with exactly 5 verdicts will silently file it as
  WRONG or RUNTIME_ERROR.
- What happens when the 150 s timeout fires is not specified at all.

Liveness has the same shape of gap: `{"type":"ping"}` arrives **every 3 seconds**
(protocol §4) and the design discards it, leaving no dead-connection detector — while a
missed broadcast is unrecoverable (protocol §11). The design also caps subscriptions at
LRU 8 with an undefined notion of "oldest", against an extension that re-`POST`s a
heartbeat every 30 s for every open tab.

## Options considered

- **Extend the verdict enum with error cases** — rejected: it conflates "the judge
  reached a conclusion" with "we failed to observe one", and pollutes every statistic
  that aggregates verdicts.
- **Discard sessions that do not terminate cleanly** — rejected outright: recording
  nothing is the failure mode the constitution ranks second-worst, right after
  recording something wrong.
- **Keep 5 verdicts, add a separate outcome dimension (chosen).**

## Decision

1. **Termination is an (action × type) matrix**, with `error` and the timeout promoted
   to explicit terminal states:

   | | submit | run |
   |---|---|---|
   | algorithm | `finish` | `result` |
   | database | `result_lesson_challenge` | `finish` |

   `error` terminates any cell. A late `finish` arriving after
   `result_lesson_challenge` is absorbed into the same session, not treated as a new one.

2. **Two non-verdict outcomes alongside the 5 verdicts**:
   - `INCOMPLETE` — timeout, disconnect, or eviction before a terminal frame. Raw frames
     are kept; the record is explicitly marked partial.
   - `UNKNOWN` — terminal frame reached but the failure message matches nothing known
     (the MLE case, or any string Programmers adds later). Never coerced into a
     neighbouring verdict.

   This mirrors the protocol layer's `Unknown(type, raw)` rule (development-rules §2.3)
   at the verdict layer.

3. **Completeness check before summarizing testcases.** Testcases arrive out of order
   because grading is parallel (protocol §5); the expected count comes from
   `test_group.testcaseIds` / `start.testcase_ids`. A session whose testcases are
   incomplete at termination is marked, not silently summarized.

4. **Liveness from ping.** No ping within a bounded multiple of the 3 s cadence means the
   connection is dead: reconnect with backoff, re-subscribe the whole active set, and log
   the gap window loudly. Sessions open at disconnect become `INCOMPLETE`.

5. **LRU respects active sessions.** A subscription with a live grading session is pinned
   against eviction; eviction otherwise orders by last heartbeat. When every slot is
   pinned, `/watch` fails explicitly rather than silently declining to observe.

6. **Subscription confirmation is not identifier validation.** A wrong `challengeable_id`
   still yields `confirm_subscription` and still runs testcases; only a generic
   `내부적인 오류가 발생했습니다` surfaces later (protocol §3 — the trap that caused four
   consecutive failures during reverse engineering). Identifiers are validated
   independently, and that generic error is treated as a probable identifier mismatch and
   surfaced loudly rather than recorded as a judging outcome.

7. **Cookie expiry is one auth state, detected at both boundaries.** The design's
   `reject_subscription` detector (design §4.3) rests on a message the protocol doc has
   never observed; a login redirect on the CodeFetch GET is an equally valid expiry
   signal. Both feed one auth state, and re-authentication attempts are bounded.

## Rationale

- Every clause above replaces an assumption with a measured protocol fact, and the two
  that cannot be settled by measurement (MLE's message, `reject_subscription`) are
  handled by refusing to guess — which is the same principle that made all protocol
  fields nullable.
- Separating "outcome" from "verdict" keeps aggregate statistics honest: a timeout that
  we failed to observe must never dilute the pass-rate of timeouts we did observe.
- Ping is a free, already-arriving liveness signal at 3 s resolution; discarding it while
  having no other detector was pure loss.

## Accepted costs

- Two extra outcome states that every consumer, query and README template must handle.
- The reconnect path can produce a duplicate observation of the same grading, which is
  why the writer deduplicates ([[decisions/2026-08-05-write-serialization]]).
- Pinning can saturate all 8 slots and reject a `/watch`; a visible failure is preferred
  to a silent non-observation, but it is still a failure the user must understand.
- Independent identifier validation costs an extra fetch or catalog lookup on a path
  that currently costs nothing.

## Outcome

Recorded 2026-08-05 as part of the design revision (#8). Related:
[[decisions/2026-08-05-capture-pipeline-stages]] · [[concepts/verdict-classification]].
