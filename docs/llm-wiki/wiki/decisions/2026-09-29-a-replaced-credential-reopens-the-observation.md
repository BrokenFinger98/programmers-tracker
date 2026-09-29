---
type: decision
project: programmers-tracker
tags: [credentials, sensor, protocol, measurement]
author: BrokenFinger98
created: 2026-09-29
updated: 2026-09-29
sources: [decisions/2026-08-11-the-session-is-checked-where-it-can-answer, sources/2026-08-11-expiry-has-no-socket-signal]
---

# A replaced credential reopens the observation

## Context

[[decisions/2026-08-11-the-session-is-checked-where-it-can-answer]] made an expired cookie
visible: the probe answers `EXPIRED`, the badge turns red and names the value to replace, and the
extension's tooltip promises that a fresh value in `.ps/session` *heals within a few minutes
without a restart*. That promise was true of the probe and false of the observation.

Measured 2026-09-29 on lesson 151136. A problem tab opened at 09:54 under a cookie that had
died; the subscription was `Started` regardless, confirmed and pinged — an unauthenticated
subscription looks exactly like a working one ([[sources/2026-08-11-expiry-has-no-socket-signal]]).
The cookie file was replaced in place at 09:58. From 10:00:40 `/watch` answered
`session: alive, subscription: live`. A PASS submitted at 10:09:47 was not recorded. `docker
restart` at 10:12:01 reopened the socket with the new cookie, and a PASS at 10:12:46 was
recorded. Nothing in the server had asked the open socket which credential it was carrying.

## Options considered

1. **Reopen every subscription on the `EXPIRED → ALIVE` transition.** Simple, and misses the case
   that matters as often: a re-login on the site rotates the cookie *before* anything reports
   expiry, so the file changes while the cached answer is still `ALIVE`.
2. **Watch the session file for changes.** A file watcher inside a container over a bind-mounted
   file is exactly the kind of platform behaviour development-rules §9.2 wants behind an
   interface, and the file is not the only provider the design allows.
3. **Remember what each socket authenticated with, and compare on the heartbeat.** The provider
   already reads the file on every call; a digest of the value is enough to tell two credentials
   apart without holding either, and the heartbeat already arrives every 30 seconds per open tab.

## Decision

Option 3. `SessionCookie.fingerprint()` is a SHA-256 of the header value. Each channel's
observation is **one object** — its job, what it has proved, and the digest it connected with —
held in one map; every change to what is held (start, stop, reopen) happens under one lock, and a
job writes only to its own object. `reauthenticate(channel)` compares the held digest with the
current one; when they differ, a successor takes the channel's place *before* the old socket is
closed, the old job is cancelled and joined with a bounded patience, and the successor is started
in a `finally` — unless a stop removed it meanwhile, in which case a stop stays a stop.
`WatchService.watch` calls it on every heartbeat. It answers two things apart: whether the
credential *changed* since the last heartbeat saw one, and whether this channel was *reopened*.
On `changed`, `SessionHealth.credentialReplaced()` forgets the cached answer and bumps a
generation, so a probe already in flight about the old cookie cannot write its answer back over
the reset. The two are separate because a socket that reconnected on its own between the
replacement and the heartbeat already carries the new cookie — nothing to reopen — while the
cached `EXPIRED` is still about the old one. Once the old socket is confirmed closed, its capture
is told the connection was lost, as the retry loop would have told it: a grading it held open is
settled incomplete and logged, instead of staying pinned in the registry with no line anywhere.

Three rules keep it honest:

- **An unreadable credential changes nothing.** A missing or empty file answers "cannot tell",
  not "different"; tearing down a working observation over a typo would cost the grading it was
  holding.
- **The digest is never logged.** It is a fact about a credential, and development-rules §7.2 is
  not about the spelling of the value.
- **A reopened observation is `PENDING`, not `LIVE`.** It has not proved anything yet; the frame
  that arrives on the new socket is what makes it live, as for any subscription.

The first cut was three maps and a `compute`. Review and an adversarial pass found the seams:
a job already replaced kept writing its health under the channel's name and a reopen read that
as "still wanted" (a stopped channel came back, 70 of 200 rounds); an interrupted reopen left a
channel held by nothing, `PENDING` forever; the race test ran its two sides one after the other
and passed for the wrong reason; and a probe in flight overwrote the reset. Each is now a test.

A second adversarial pass on the redesign held every concurrency invariant over 520 rounds and
found three behaviours instead: the old socket's grading stayed pinned (above), the credential
read swallowed the caller's cancellation, and cache invalidation was tied to "reopened" rather
than "changed". Each is now a test as well.

## Rationale

The socket has no expiry signal — that was measured, and it is why the check moved to an HTTP
probe. The same measurement says a socket also has no *replacement* signal: nothing on it will
ever say the credential behind it is stale. So the comparison has to be made by the one party
that knows both values, the provider, at the one moment that recurs, the heartbeat. Everything
this decision adds sits on paths that already exist: the provider is already read per attempt,
the heartbeat already reaches `WatchService`, and the cache already has a lock.

## Accepted costs

- **Every attempt reads the session file once more** (the client reads it again when it
  connects). Two reads of a 32-byte file per reconnect, and one per heartbeat.
- **A grading in flight on the old socket is abandoned.** By construction there is none — the old
  socket was receiving nothing — but a cookie replaced while a *working* socket is mid-grading
  would drop that grading. The replacement is the owner's act, and the alternative is the status
  quo: keep the dead one.
- **In-place replacement is still required in Docker.** The file is bind-mounted by inode; an
  editor's rename-on-save leaves the container reading the old file, and this decision cannot
  see what the container cannot see. `docs/bootstrap.md` says so.
- **After the patience (5 s) the successor opens while the old observation may still be
  finishing its last frame** — a push on the way out of a grading can hold it. It cannot receive
  another frame: a `flow` builder's `emit` checks cancellation, which is the kotlinx.coroutines
  contract `ActionCableClient.observe` relies on. What overlaps is one capture finishing and
  another starting, never one frame reaching two captures. Stated here rather than assumed away.
  On that path the old capture is *not* told the connection was lost — a collector may still be
  inside it — so a grading it held open stays pinned until the next grading on that channel or a
  restart. Rare (a close that takes over five seconds) and logged as a warning when it happens.
- **A torn read of the session file is now a trigger.** The heartbeat reads the file each time;
  a paste through a non-atomic editor can be seen half-written, costing a reconnect onto a
  truncated cookie and a second one onto the whole. Thirty seconds of a red badge at the worst
  moment. `docs/bootstrap.md` prescribes the in-place `printf`, which is a single write.
- **During the reopen the badge is green.** A successor reads `PENDING`, which the extension
  shows as *watching*; the session axis of the same answer, probed with the new cookie, is what
  says whether anything will be recorded. Chosen above; recorded here because it was asked.
- **The heartbeat now reads the credential file once per call**, on the IO dispatcher. Microseconds
  for a file; a Keychain-backed provider, when one exists, will need its own cache.
- **`SubscriptionRegistry` still assumes a single writer** while `/watch` runs one `runBlocking`
  per request — a pre-existing gap the adversarial pass found beside this change, filed separately.

## Outcome

Issue #331. Layer tests on the subscriber (reopen after the old observation closed, a reopen
racing a stop leaves nothing running, unreadable credential, nothing held), the watch service
(the cache is forgotten at once) and the session health (a forgotten answer is asked again).

The review found the first cut cancelled the old job without waiting, so two collectors could
briefly share a channel — `ChannelCapture` is one-per-collector by contract. The decision gained
its lock and its bounded join because of that, and the ordering is now a test, not a comment.

**Measured 2026-09-29 10:47, container on the branch image, never restarted.** The cookie file
was replaced in place with a junk value: the same heartbeat logged *credential changed —
reopening the observation of lesson 151136*, answered `session: expired` (the five-minute cache
forgotten at once) and `subscription: pending`. The original bytes were restored in place: the
next heartbeat reopened again, answered `session: alive`, and the subscription read `live` three
seconds later. Container status throughout: `Up 48 seconds (healthy)`.

The same cycle on the tab the owner was on, lesson 133024 (10:51:19 junk, 10:51:20 restored,
two reopens logged), and then a submit at 10:52:44 **recorded through the reopened socket** —
attempt 3, PASS 1/1. That is the item the issue was opened for.
