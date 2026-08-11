---
type: source
project: programmers-tracker
tags: [protocol, credentials, measurement, failed-attempts, sensor]
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-11-expiry-has-no-socket-signal.md]
---

# 2026-08-11 — measuring what an expired session does

## Key claims

1. An **invalid** session cookie gets `confirm_subscription` in 0.49 s and pings every ~3 s
   indefinitely. `reject_subscription` never arrives.
2. On the identical identifier at the same moment, the valid session received the whole run
   (`start`, `testcase` ×2, `result`) and the invalid one received **zero** broadcasts.
3. Therefore the socket carries **no signal for session expiry at all** — confirm, ping cadence
   and socket health are identical between a working session and a dead one.
4. `SubscriptionHealth.REJECTED`, shipped hours earlier, is dead code for the scenario its own
   ADR names. What survives of that change is unreachable-socket detection, which is real but is
   not the failure anyone feared.
5. Protocol §10 was corrected: streams are scoped by channel parameters **and by the
   connection's authenticated identity**. Its two verifications had both used the same cookie.
6. Detection must come from an authenticated HTTP request, and the problem page does not qualify
   — §3 records that it yields identifiers without login.
7. The fix made the wrong answer *more* confident: `started` was vague, `subscription: "live"`
   is a reason to believe.

## Pages this source updated

[[decisions/2026-08-11-a-watch-answer-is-not-a-promise]] · [[concepts/assumption-vs-measurement]] ·
[[concepts/tests-that-explain-defects]]
