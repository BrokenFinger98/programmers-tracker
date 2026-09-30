---
type: source
project: programmers-tracker
tags: [credentials, sensor, concurrency, measurement, failed-attempts]
created: 2026-09-30
updated: 2026-09-30
sources: [raw/sessions/2026-09-29-a-session-expiry-and-the-socket-that-looked-alive.md]
---

# 2026-09-29 session summary — a session expiry, and the socket that looked alive

## Key claims

1. **A replaced cookie healed the probe and not the observation.** A subscription opened under a
   dead cookie stayed confirmed, pinged and empty after the file was replaced; a PASS at 10:09:47
   was lost while `/watch` said `alive` and `live`. The socket has no expiry signal
   ([[sources/2026-08-11-expiry-has-no-socket-signal]]) and therefore no replacement signal either.
2. The fix compares a digest of the credential on every heartbeat and reopens the observation
   ([[decisions/2026-09-29-a-replaced-credential-reopens-the-observation]]). Measured live before
   merge: junk in the file → reopen + `expired` on the same heartbeat; original restored → reopen +
   `alive`; a submit recorded through the reopened socket.
3. **The first cut was wrong three ways, and the test that should have caught the worst one did
   not race.** Three maps and a `compute` let a replaced job write health under the channel's name;
   a stopped channel came back in 70 of 200 rounds; the race test ran its two sides serially.
4. The second adversarial pass held every invariant (520 rounds) and found behaviours instead: a
   grading pinned by the closed socket, a swallowed cancellation, cache invalidation on the wrong
   axis. Each is a test now.
5. The owner's follow-up — let the browser supply the cookie — became #332; the compose secret
   mount was read-only, so the file moved to the writable directory mount. Trigger 2 measured the
   same afternoon: `expired` → the extension read the browser's cookie → posted → file rewritten →
   `alive`, one second.

## Pages this source updated

[[decisions/2026-09-29-a-replaced-credential-reopens-the-observation]] ·
[[decisions/2026-09-29-the-sensor-hands-over-the-session]] ·
[[decisions/2026-08-11-the-session-is-checked-where-it-can-answer]] ·
[[sources/2026-08-11-expiry-has-no-socket-signal]] · [[concepts/assumption-vs-measurement]] ·
[[concepts/tests-that-explain-defects]]
