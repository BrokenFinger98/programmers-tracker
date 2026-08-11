---
type: decision
project: programmers-tracker
tags: [credentials, sensor, protocol, measurement, courtesy]
author: BrokenFinger98
created: 2026-08-11
updated: 2026-08-12
sources: [raw/sessions/2026-08-11-expiry-has-no-socket-signal.md, decisions/2026-08-11-a-watch-answer-is-not-a-promise]
---

# The session is checked where it can answer, not where we were looking

## Context

[[decisions/2026-08-11-a-watch-answer-is-not-a-promise]] shipped a health state built on
`reject_subscription`, and hours later measurement showed that frame never arrives: an
unauthenticated subscription is **confirmed in 0.49 s, pinged normally, and receives nothing**
(protocol §15.3). So from the moment a cookie dies every grading is lost, and every signal the
observation itself has — confirm, ping cadence, socket liveness — is identical to a working one.

The socket cannot answer the question. Something else has to.

## Options considered

Protocol §14 listed three candidates and marked all three **unmeasured**. Choosing among them
from reasoning is exactly how the previous attempt went wrong, so they were measured first —
four endpoints, with and without the cookie, three alternating runs (§15.4).

| endpoint | signed in | signed out | verdict |
|---|---|---|---|
| lesson page | 200, 24797 B | **200**, 21971 B | §3 already records it needs no login; only the *content* differs, which is a fragile signal |
| `solution_groups` | 200 | 302 → login | clean, but problem-scoped and measured 401-until-solved (2026-08-10), so what it answers depends on the problem |
| `challenges?statuses[]=solved` | 200, 510 B | **200**, 184 B | an emptiness indistinguishable from a user who has solved nothing |
| **`open-challenge-activities`** | **200** | **401** | user-scoped, problem-independent, JSON both ways |

A fifth option — infer expiry from the code fetch already happening after each grading — was
rejected: it only fires *after* a grading, which is after the loss it should have prevented.

## Decision

`SessionActivityProbe` asks `GET /api/v1/main/open-challenge-activities?year=<current>` and maps
**200 → `ALIVE`, 401 → `EXPIRED`, anything else → `UNKNOWN`**. `/watch` answers with it beside
`subscription`, and the badge shows red `!` on `EXPIRED` naming the value to replace.

Three rules make it honest:

- **`UNKNOWN` is never folded into `EXPIRED`.** A 5xx, a throttle or a dead network says nothing
  about the credential. Telling someone to replace a working cookie is how the one message that
  matters gets ignored.
- **The body is shape-checked, not parsed.** A 200 would be the whole signal if it meant what it
  says, and §14 records that this API family throttles as 200-with-HTML. No field is read: once
  the body is well-formed JSON the status does answer the question (amended by #191).
- **`UNKNOWN` is not cached.** Every other answer is held for five minutes, because the extension
  posts `/watch` every 30 seconds *per open tab* and probing on each would put a request to
  Programmers every few seconds — well past development-rules §9.3's "the same level as a
  browser". A failed probe is not remembered: holding a nothing for five minutes reports nothing
  useful for five minutes.

`subscription: live` beside `session: expired` is a real combination rather than a contradiction:
a perfectly healthy socket is worth nothing if the server on the other end is not you.

## Rationale

The endpoint was chosen by measurement and the alternatives were rejected on measured grounds,
which is the whole correction this decision exists to apply. The previous attempt reasoned about
what a judge *ought* to send on an expired session; this one asked four endpoints what they
actually do.

Five minutes is chosen, not measured. A tab open all evening costs ~12 requests an hour, and a
cookie dying mid-session is noticed inside one problem rather than after it.

## Accepted costs

- **A dead cookie is invisible for up to five minutes**, and up to a whole grading can be lost in
  that window. Shorter means more traffic; the interval is the dial and it is stated rather than
  hidden.
- **The probe is a request Programmers did not ask for.** It is one small authenticated GET per
  five minutes while a problem tab is open, and zero when the browser is closed — but it is
  traffic on their servers for our benefit, which §9.3 says to state plainly.
- **`UNKNOWN` shows nothing on the badge.** A user whose network is down sees no warning about a
  cookie whose state is genuinely unknown. Preferred over crying wolf, and it means an outage and
  a healthy session look the same from the toolbar.
- **The year is a client-side clock.** A machine whose date is wrong asks about the wrong year;
  the endpoint still answers 200/401 on authentication, so the signal survives, but the request
  is meaningless in every other way.
- **One more endpoint we depend on.** If Programmers removes it, the check degrades to `UNKNOWN`
  forever. ⚠️ (old) — "silently … Nothing currently notices a probe that has answered `UNKNOWN`
  for a week." **Closed the same day by #189**: a run of `UNKNOWN` longer than thirty minutes is
  warned about once, and its end once. The dependency remains; the silence does not.

## Outcome

Verified live on both branches, which is the standard the previous attempt did not meet:

| | `/watch` answered |
|---|---|
| real cookie | `{"subscription":"pending","session":"alive"}` |
| deliberately invalid cookie | `{"subscription":"pending","session":"expired"}` + the server's own warning |
| restored | `{"session":"alive"}` |

The cookie file was copied out, compared by hash before and after, and restored to a byte-identical
value — the failure branch is the one worth exercising, and exercising it must not cost the
credential.

Sixteen tests: every status class including the two that must not read as expired, the URL shape,
that the request carries nothing about the user, and the cache — including a failed probe that
must not leave a stale `ALIVE` standing.


---

## Extended 2026-08-11 (#189): a check that cannot answer says so

The endpoint is one Programmers never promised us, so the interesting failure is not a wrong
answer but **no answer, forever**: it moves or starts returning 403, every probe reads `UNKNOWN`,
the badge stays quiet by design, and the detection above is simply gone.

The project already had the rule for messages — an unrecognised type is kept as `Unknown(type,
raw)` **and warned about**, because that is the only way to notice a protocol change. The session
check had the first half and not the second.

`SessionHealth` now tracks how long it has been unable to answer and reports the transition once
in each direction, on the same reasoning as the backup all-clear
([[decisions/2026-08-11-a-record-on-one-disk-says-so]]): a warning with no end leaves a reader
unable to tell a fixed problem from an unreported one.

Two judgements worth stating:

- **Thirty minutes**, chosen not measured, so a laptop losing wifi mid-problem stays silent while
  a protocol change is noticed the same session it happens.
- **`EXPIRED` ends the run.** It is the check *working* — the temptation to treat any non-`ALIVE`
  as trouble would fire the protocol-change warning at exactly the moment the tool is doing its
  job.
- **No badge state**, because the vocabulary is full and "we cannot tell whether you are
  recording" is a diagnostic for whoever reads logs, not something to put in front of someone
  mid-problem.
