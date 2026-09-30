# 2026-09-30 — the sensor hands the session over

Raw session record. Immutable (wiki schema §1). 08:23–09:00 KST plus the previous afternoon's
review loop; from the live context of session `b240e44e`. Continues
`2026-09-29-a-session-expiry-and-the-socket-that-looked-alive.md`.

---

## The review and the adversarial pass on #332, taken before the live test

Reviewer, three blocking: the host permission named `school.programmers.co.kr` while the cookie
is scoped to `.programmers.co.kr` — Chrome delivers `cookies.onChanged` only to an extension whose
host permission covers the cookie's own domain, so trigger 1 could never fire (and had not, at a
re-login the afternoon before); `.env.example` and the troubleshooting table still described a
`TRACKER_SESSION_FILE` that the compose change had silently retired; the ADR's outcome claimed a
verification that was pending.

Critic: **`{"cookie": null}` was written into the credential file as the word `null`** —
`JsonNull` is a `JsonPrimitive` whose `content` is the four characters, and the answer said
`persisted: true`; **the once-per-value dedupe blocked the repair** — a server that had lost the
cookie kept asking and the sensor kept refusing to re-send the same value, forever within a
worker's life; a failed persist was never retried and a memory value shadowed a hand-pasted file
for good; `Files.exists` turned an unsearchable parent into "paste a cookie"; `contains('=')`
misread base64 padding as a full header; every rotation of the cookie would reopen the socket
mid-grading. And a local process squatting the port would receive the cookie — true, and stated
as a cost rather than fixed: it runs as the owner, and `.ps/session` is readable by the owner
already.

All taken: strings only and cookie-octets only; no dedupe on the server-asked path; retry on the
next hand-off and the file wins once it changes by hand; absent and unreadable told apart; the
prefix decided by the cookie's name; web origins refused (403); a heartbeat during a grading
defers the reopen. Then Windows CI failed four provider tests that deny a write with POSIX
permissions — none on Windows — and they assume a POSIX file system now, as `GithubRemoteTest`
does.

## Trigger 1, measured

The owner reloaded the extension and signed out and in. At **08:23:09.693** the server logged
*handed over: changed=true, persisted=true* with **no `expired` answer anywhere before it** — the
change trigger, not the server asking. The open tab's channel was reopened at 08:23:22; a submit
at 08:23:36 was recorded (lesson 133024, attempt 4, PASS); a restart at 08:24:36 booted on the
cached file, `alive` at once, zero hand-offs.

The same sign-in produced **103 hand-offs in 33 seconds, one of them carrying a new value**:

```
   9 08:23:00     12 08:23:05     20 08:23:10     22 08:23:20     11 08:23:32
  15 08:23:01      1 08:23:06      2 08:23:19      1 08:23:22      1 08:23:33
```

A sign-in re-sets the same cookie on many responses and each set is an `onChanged` event.
Harmless server-side — an equal value is neither written nor probed — but noisy, so the change
trigger now hands a value over only when it differs from the last change seen; the server-asked
trigger stays unconditional, which is what keeps recovery working. PR #336 merged at 08:41.

## The two defects found beside the work, shipped the same morning

- **#333 — the registry had one lock's worth of assumption and none of the lock.** Written for one
  writer; `/watch` had become one `runBlocking` per request. Sixteen concurrent admissions into a
  capacity of four left **five** entries on the first run of the new test; a heartbeat refresh
  racing a pin could overwrite the pin. Every operation is atomic under one lock now
  ([[decisions/2026-09-30-the-registry-has-one-lock]], PR #337).
- **#334 — eight tabs, eight probes.** The cache held its promise for one caller at a time. The
  first caller past an empty cache owns the probe and the rest await its answer; a probe that
  throws releases them and they ask for themselves
  ([[decisions/2026-09-30-one-probe-in-flight]], PR #338).

Container rebuilt from main at 08:59:51. Nothing open from the run.

## What the two days settled about the tool

The session cookie is no longer something the owner handles. A red badge means "sign in to
Programmers again"; the sensor does the rest, the server reopens what it holds, and the file is a
cache the server writes. The manual paste remains, as §9.2 requires, for a browser without the
extension.
