---
type: decision
project: programmers-tracker
tags: [credentials, sensor, extension, courtesy]
author: BrokenFinger98
created: 2026-09-29
updated: 2026-09-29
sources: [decisions/2026-09-29-a-replaced-credential-reopens-the-observation, decisions/2026-08-11-the-session-is-checked-where-it-can-answer]
---

# The sensor hands over the session

## Context

When Programmers signs the owner out, they sign in again on the site and get a new
`_session_production`. Until now the tool then needed a manual step — DevTools, copy, `printf`
into `.ps/session` — that nobody thinks of until the badge has been red for a while, and the
gradings in between are lost. On 2026-09-29 the badge had been red since 09:50 before it was
noticed; one solve was lost to the gap and a second to the socket that #331 then fixed.

[[decisions/2026-09-29-a-replaced-credential-reopens-the-observation]] made *replacing* the
cookie enough. This decision is about who replaces it. The browser is the only party that ever
holds a fresh one: the server cannot ask Programmers for a cookie, and re-login rotates it.

## Options considered

1. **The server reads the browser's cookie store.** Chrome encrypts its cookies with a key in
   the OS keychain; the server runs in a container that cannot reach either. The manual-file
   provider exists because this route is not portable (development-rules §9.2).
2. **The owner keeps pasting.** The status quo, with a better badge. The badge was already
   explicit; the step was still missed.
3. **The extension hands it over.** The sensor already runs in the signed-in browser and already
   talks to the server every thirty seconds under a token. Reading one cookie is one permission
   away.

## Decision

Option 3, with two triggers in the extension and one rule on the server.

- **Trigger 1 — the cookie changed.** `chrome.cookies.onChanged` for `_session_production` on
  `programmers.co.kr` wakes the service worker, which posts the new value at once — seconds after
  a re-login, before any probe has noticed anything.
- **Trigger 2 — the server is not authenticated.** A `/watch` answer whose `session` is `expired`
  or the new `missing` (a server that holds no credential at all, e.g. first boot) makes the
  sensor read the cookie and post it. The server ignores an equal value (no write, no probe), so
  the sensor keeps no memory of what it sent: the first cut had one, and it blocked the repair
  when the server had lost the cookie — it kept asking, the sensor kept refusing to re-send.
- **`POST /session`**, loopback, under the same `X-Tracker-Token`, body `{ "cookie": … }`. The
  server hands the value to the one `ManualFileSessionProvider`, which writes it atomically and
  owner-only beside the file it already reads; if the file cannot be written the value is served
  from memory and the answer says `persisted: false`. On a change it forgets the cached session
  answer and probes at once, so the sensor learns in the same round trip whether the cookie it
  found is any good. The answer is a shape — `changed`, `persisted`, `session` — never the value.
- **Reopening is left to the heartbeat.** The sensor sends one right after a hand-off that
  changed something, so #331 does the rest within a second instead of thirty.
- **The session file stays, as a cache the server writes** — so a restart while the browser is
  closed still has the last credential — and as the manual path §9.2 requires for environments
  without the extension. In `compose.yaml` it moves from a read-only Docker secret to the
  already-mounted `.ps/` directory: same host file, now writable, and no longer bind-mounted by
  inode, which retires the in-place-only rule for new setups.
- **`SessionState.MISSING`** is its own state, because its remedy is different from `EXPIRED`'s:
  not "replace it" but "sign in, and the sensor hands it over". It is unauthenticated, like
  `EXPIRED`; `UNKNOWN` stays authenticated, as before.

## Rationale

Every piece already existed but the hand-off: the signed-in browser, the loopback endpoint, the
token, the file, and — since #331 — a server that reopens on a changed file. The decision adds
one endpoint and one permission, and takes away the step that was being missed. Reading the
cookie in the extension is the portable form of what §9.2 calls browser auto-extraction, done
where the browser's own API makes it a one-liner rather than a keychain decryption.

## Accepted costs

- **The extension gains the `cookies` permission and a host permission for `*.programmers.co.kr`.**
  The wildcard is not generosity: the cookie is scoped to `.programmers.co.kr`, and Chrome hands
  `cookies.onChanged` only to an extension whose host permission covers the cookie's own domain —
  with `school.` alone, trigger 1 never fires (review of #332). It reads one named cookie, on two
  triggers, and sends it to `127.0.0.1` under the token. This is
  stated in `extension/README.md` and `SECURITY.md`, and the decision table it acts on is a file
  of its own, tested with `node --test`. Updating from an older version means reloading the
  unpacked extension and accepting the permission; Chrome disables it until then.
- **The cookie crosses loopback HTTP once per change.** The same wire the token already crosses
  on every heartbeat, and the endpoint answers nothing that could be replayed.
- **A compose change for existing installs.** The file is the same one on the host; the container
  reads it from a different mount. Nothing to migrate, but an old `compose.yaml` keeps the
  inode-mounted secret and the in-place-only rule with it, which `docs/bootstrap.md` notes for
  that case.
- **The server writes a credential file it used to only read.** Atomically, owner-only, and only
  what the sensor handed over under the token. The value reaches no log; the path does. A write
  that fails is served from memory, said so in the answer (`persisted: false`, which the badge
  shows), tried again on the next hand-off, and never shadows a file someone changed by hand.
- **Nothing proves to the extension that the listener on the port is this server.** A local
  process that took the port first would receive the cookie. It runs as the owner, and
  `.ps/session` is readable by the owner already, so the hand-off grants it nothing new; the
  endpoint still refuses web origins beside the token, as `/mcp` does. Stated, not assumed away.
- **How often Programmers rotates `_session_production` in ordinary use is unmeasured.** Every
  rotation is one hand-off, one probe and one reopen. The reopen is the part that could cost a
  grading, so a heartbeat during a grading defers it until the grading settles; the server log
  carries one line per hand-off, which is the measurement — read it after a week.

## Outcome

Issue #332. Web slice test for `/session` (token, blank, control characters, non-object body,
equal value, and the value never echoed); provider tests for an atomic owner-only write, a
missing file created, a read-only location served from memory; probe maps a missing credential
to `MISSING`; the extension's decisions under `node --test`.

**Measured 2026-09-29 12:33, trigger 2, container on the branch image, no manual step.** The
session file was replaced in place with a junk value at 12:33:49. The extension's next heartbeat
got `expired`; at 12:33:50 the server logged *The sensor handed over a session cookie: changed=true,
persisted=true* and the file was rewritten (owner-only, 32 bytes) with a value that differed from
the owner's previous cookie — the browser held a fresher one from a re-login earlier that day —
the heartbeat was re-sent, the observation reopened with it, and `/watch` answered `alive`. One
second, end to end.

**Measured 2026-09-30 08:23, trigger 1, no manual step.** The owner signed out of Programmers
and in again with the extension loaded (host permission `*.programmers.co.kr`). At 08:23:09.693
the server logged *handed over: changed=true, persisted=true* and the file was rewritten — with
no `expired` answer anywhere before it, so this was the change trigger, not the server asking.
The other open tab's channel was reopened at 08:23:22; a submit at 08:23:36 was recorded (lesson
133024, attempt 4, PASS) through a socket opened after the hand-off. A restart at 08:24:36 booted
on the cached file: `session: alive` at once, zero hand-offs needed.

The same sign-in produced **103 hand-offs in 33 seconds, one of them carrying a new value**: a
sign-in re-sets the same cookie on many responses, and each set is a `cookies.onChanged` event.
Harmless server-side (an equal value is neither written nor probed) but noisy, so the change
trigger now hands a value over only when it differs from the last change seen; the server-asked
trigger stays unconditional, which is what keeps recovery working.
