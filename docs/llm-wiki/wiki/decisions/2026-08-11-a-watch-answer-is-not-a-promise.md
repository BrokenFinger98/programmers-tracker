---
type: decision
project: programmers-tracker
tags: [capture, sensor, credentials, failure-taxonomy, api]
author: BrokenFinger98
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-11-expiry-has-no-socket-signal.md, raw/sessions/2026-08-07-adversarial-review.md, decisions/2026-08-05-failure-taxonomy, decisions/2026-08-10-sensor-observations]
---

# A `/watch` answer is not a promise that anything is being watched

## Context

`POST /watch` returned `{"status":"started"}` with 200 whenever the registry accepted the
channel. Subscribing is fire-and-forget by design — `jobs.computeIfAbsent { scope.launch { … } }`
— so the request returns before the socket has done anything at all, and it returned the same
answer whether the subscription confirmed, was refused, or never opened.

Found as M1 on 2026-08-07 (`raw/sessions/2026-08-07-adversarial-review.md`) and still open four
days later. `extension/background.js` named it in its own comment: *"and the /watch-always-200
gap it does not fix"*.

**The path that makes it silent rather than merely imprecise**: `PageProblemIdentityResolver`
caches a resolved problem by lesson id **forever**, so for a problem the server has already
seen, an expired cookie never touches the page fetch that would have raised
`UnresolvableProblemException`. It goes straight to the socket, the judge refuses, the retry
loop retries forever, and `report()` logged only `cause.javaClass.simpleName` — discarding the
one sentence that says what to do.

Green badge, 200 on every 30-second heartbeat, a warn line indistinguishable from a flaky
network, and every grading lost. This is the constitution's worst outcome in its purest form:
the tool reporting health while recording nothing.

## Options considered

1. **Make `/watch` block until the subscription confirms.** Honest, and wrong: the socket is
   long-lived and the request is not, a confirm can take a second on a cold connection, and a
   subscription can die at 14:00 having confirmed at 09:00. It answers the question only for
   the instant of the request.
2. **Fail the request when the subscription is not live.** Turns a working sensor into a 502
   during any transient reconnect, and the extension's error path says "is the server running?"
   — which would be a lie.
3. **Log it better and leave the contract alone.** The status quo plus effort. Nothing the user
   sees changes, and the badge is the entire user interface.
4. **Answer with both: what the request did, and what the socket says.** Chosen.

## Decision

`watch()` returns `WatchStatus(outcome, health)`, and `/watch` carries `subscription` beside
`status`. Health is one of four:

| | |
|---|---|
| `PENDING` | subscribed; nothing back yet. Counts as observing |
| `LIVE` | a frame arrived on the current attempt |
| `REJECTED` | the judge refused — **the same cookie cannot fix it** |
| `UNREACHABLE` | the socket keeps failing for some other reason |

Three rules make it honest rather than decorative:

- **Absent means `UNREACHABLE`, never `PENDING`.** A channel the subscriber holds no job for is
  not being watched. The optimistic default is the entire defect.
- **A failure is not reset per attempt, only cleared by a frame.** The retry loop runs
  continuously; re-marking `PENDING` at the top of each attempt would make a refusal blink out
  of view every second, restoring the defect while passing every other test.
- **An attempt that ends without a single frame demotes.** The measured ~30-minute silent close
  throws nothing, so nothing else would notice; `PENDING` is demoted too, because once a whole
  attempt has come and gone the "it is only a moment old" excuse is spent.

**The badge outranks the record state with it.** A red `!` beats a green `✓`: a record from an
hour ago is true and irrelevant if nothing is being watched now. The question the badge answers
is *"will my next submit be recorded"*.

The two failures stay separate all the way to the tooltip because they ask the user for
different things — replace `.ps/session`, or wait. Collapsing them would be honest and useless.

## Rationale

`PENDING` counting as observing is the one judgement call worth defending. It is optimistic,
which is what caused the original bug — but the alternative alarms on the first heartbeat of
every problem the user opens, and a badge that cries wolf on ordinary use is a badge people
learn to ignore. The exposure is bounded by the demotion rule: `PENDING` survives exactly one
attempt, and the judge pings every ~3 seconds, so a healthy channel leaves it almost
immediately.

## Accepted costs

- **The answer is a snapshot, not a subscription to health.** It reports what was true when the
  heartbeat arrived, so a subscription that dies at 14:00:05 is reported at 14:00:30. Thirty
  seconds of silence is the extension's polling interval, not a new gap.
- **A transient reconnect can flash red.** The first retry is ~1 s, so it takes a failure that
  spans a heartbeat to be seen — mostly real ones. Stated rather than smoothed, because
  smoothing means adding a "has it been failing for a while" clock, and a hidden grace period
  is how a warning becomes a warning nobody sees.
- **`SubscriptionHealth` is a fifth thing the badge has to explain.** The vocabulary is at its
  limit; another state should replace one rather than be added.
- **The rejection reason no longer carries the channel identifier.** Losing it costs a little
  when debugging two channels at once, and it settles the same review's MINOR: an exception
  message was the one place `StoredChannel`'s stated policy was contradicted.

## Outcome

Four healths, four failure-path tests, and one that exists only to catch the plausible
regression — a refusal must survive the reconnect that follows it. The badge gains `blind`, and
`extension/README.md` says that red `!` outranks everything else.

Not closed by this: whether the *user* ever sees it depends on the extension being loaded. A
server watched by nothing still answers `started` to nobody. That is the sensor's job and it
already has a badge for it ([[decisions/2026-08-10-sensor-observations]]).


---

## ⚠️ Amended the same day (#175): `REJECTED` is unreachable for the case it was built for

Measured hours after this shipped, and it undoes the headline claim.

Two observers on the **identical** channel identifier at the same moment — one with the real
session, one with an invalid string:

| | confirm | pings | broadcasts |
|---|---|---|---|
| valid session | 1 | 110 | **4** — the whole run |
| invalid session | 1 | 160 | **0** |

The invalid-session subscription was **confirmed in 0.49 s** and pinged normally throughout.
`reject_subscription` never arrived. It does not exist for this case (protocol doc §15.3).

So on an expired cookie:

- a ping is a frame, so `health` reaches **`LIVE`**
- the badge is **green**
- and nothing is recorded

**This decision's `REJECTED` state is dead code for session expiry.** What it still does is
real — a socket that cannot connect, or an attempt that ends without a frame, now demotes and
reaches the badge, and those were previously invisible too. But the motivating scenario in the
Context above ("when the session cookie expires…") is *not* solved by it.

⚠️ (old) — "The two failing states are kept apart because they ask the user for different
things — `REJECTED` means paste a fresh session cookie". `REJECTED` still means that **if it
ever fires**; nothing observed has made it fire.

The deeper lesson is the one this ADR half-stated and did not follow through: an answer must
report what was measured, not what the design expects. `Step.Fail` was wired to a frame nobody
had seen, and building a health state on it produced a *more* confident wrong answer than the
unconditional `started` it replaced — green with a reason behind it.

Detection has to come from an authenticated HTTP request, and what to use is unmeasured — see
#175 and protocol §14.
