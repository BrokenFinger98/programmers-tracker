# 2026-08-11 — an expired session has no socket signal, measured

Raw session record. Immutable (wiki schema §1).

The last item on a "remaining risk" list turned out to be the biggest finding of the day. #168
had shipped hours earlier saying `/watch` now reports the socket's own verdict so an expired
cookie stops looking like a working sensor. The remaining risk it stated was *"not verified
against a live expired cookie"*. Verifying it undid the claim.

---

## The question the wiki had been carrying since 2026-08-05

[[concepts/assumption-vs-measurement]], claim #1 of four:

> "`reject_subscription` is the measured signal for cookie expiry" — The protocol document never
> mentions `reject_subscription` **anywhere**. The design (§4.3) had built the entire
> expiry-detection mechanism on a message no one had ever seen.

Six days later `SubscriptionHealth.REJECTED` was built on the same frame, and the badge state
that tells a user to replace `.ps/session` with it. Still nobody had seen it.

---

## Method

Two `liveObserve` processes on the **identical** channel identifier
(`algorithm 120802 14641 java`), running simultaneously:

- one reading the real session file
- one reading a scratch file containing an obviously invalid string

`liveObserve` honours `TRACKER_SESSION_FILE`, so the real `.ps/session` was never modified and
never printed. One `run` — not a submit — triggered in the browser on a Lv0 problem, so the
account record is untouched.

The identifiers came from the problem page fetched **with no cookie at all** (protocol §3), which
is itself a reminder of what the page does and does not prove.

---

## Result

| observer | `confirm_subscription` | pings | broadcasts |
|---|---|---|---|
| valid session | 1, at 1.61 s | 110 | **4** — `run/start`, `run/testcase` ×2, `run/result` |
| invalid session | 1, at **0.49 s** | 160 | **0** |

```
[0.49s confirmed] {"identifier":"{…\"lesson_id\":120802}","type":"confirm_subscription"}
[1.74s ping] [4.69s ping] [7.69s ping] … [28.69s ping]
```

The unauthenticated socket was confirmed faster than the authenticated one, pinged for the whole
observation, and was never rejected. It simply received nothing.

---

## What it costs

**`SubscriptionHealth.REJECTED` is dead code for session expiry.** A ping is a frame, so health
reaches `LIVE`, the badge is green, and every grading is lost. The fix shipped that morning does
not solve the scenario named in its own Context.

What #168 still does is real — a socket that cannot connect, and an attempt that ends without a
frame, now demote and reach the badge. Both were invisible before. But those are not the failure
anyone was worried about.

**It also corrects a measured section.** Protocol §10 read:

> Every client subscribed to the same `identifier` receives identical messages simultaneously.
> This is because ActionCable streams are scoped by channel parameters, **not by connection**.

Both of its verifications used the same valid cookie, so what they measured was *two sockets,
one user*, and the conclusion generalised past the evidence. Streams are scoped by channel
parameters **and by the connection's authenticated identity**.

---

## The shape, again

This is [[concepts/tests-that-explain-defects]] one layer out from tests. The pattern there is a
green suite that agrees with the defect; here it is a **health signal that agrees with the
failure**. Confirm, ping cadence, socket liveness — every signal a passive observer has is
byte-for-byte identical between a working session and a dead one.

And #168 made it worse before this was measured: an unconditional `started` is obviously
uninformative, while `subscription: "live"` is a *reason to believe*. A confident wrong answer
beat a vague one, which is the failure mode the whole project is organised against.

The generalisable line: **a health check must be built on a signal that has been observed to
differ between health and failure.** `Step.Fail` was wired to a frame nobody had seen. Nothing
in the code review could catch that — only running it against a broken credential.

---

## Also observed

The server logged `Refused an unauthorized /watch request` throughout. The extension's stored
watch token no longer matches the server's, so the sensor on this machine has been blind —
separately from everything above, and not noticed until an unrelated log was read.
