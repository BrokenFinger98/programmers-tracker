# 2026-09-29 — a session expiry, and the socket that looked alive

Raw session record. Immutable (wiki schema §1). 09:50–12:45 KST, from the live context of session
`b240e44e`. Continued the next morning in
`2026-09-30-the-sensor-hands-the-session-over.md`.

---

## How it started

The owner opened a problem tab and the sensor badge showed red: *your Programmers session has
expired — nothing is being recorded*. The container had been up for eight days, healthy, and had
pushed its daily backup at 23:00 the night before; the cookie in `.ps/session` was from 08-04,
eight weeks old. The only thing wrong was the credential.

The container was found the way the memory now says to find it: not as a JVM process but as
Docker's backend on port 1619 — the compose container, with `.ps/session` bind-mounted **read-only,
by inode**, as a secret. That mount shape mattered twice today.

## The paste healed the probe and not the observation (→ #331)

The owner pasted a fresh cookie in place at 09:58. The probe caches its answer for five minutes,
so `/watch` said `expired` until 10:00:39 and `alive` from the next heartbeat. The owner solved
lesson 151136 at 10:09:47 — PASS on the site — and **nothing was recorded**, while `/watch`
answered `session: alive, subscription: live, status: refreshed`.

The subscription had been opened at 09:54 under the dead cookie. An unauthenticated subscription
is confirmed, pinged and empty ([[sources/2026-08-11-expiry-has-no-socket-signal]]); the badge's
promise *heals within a few minutes without a restart* was true of the probe and false of the
observation, and nothing in the server ever asked an open socket which credential it carried.
`docker restart` at 10:12:01 reopened the socket; a PASS at 10:12:46 was recorded. Two solves
were lost to the same expiry: the one before the paste and the one after it.

## The fix, and the two passes that reshaped it

Each observation now remembers a SHA-256 digest of the credential it connected with; the heartbeat
compares it with the current file and reopens the observation when they differ
([[decisions/2026-09-29-a-replaced-credential-reopens-the-observation]]). The first cut was three
maps and a `compute`. The reviewer found the old job was cancelled without being joined, so two
collectors could briefly share a channel, and that the plan put merge before the live check — the
exact pattern this issue is about. Then the adversarial pass broke the first cut properly:

- **A stopped channel came back, 70 of 200 rounds.** The replaced job kept writing its health under
  the channel's *name*, and the reopen read that as "still wanted". The race test could not see it
  because it ran its two sides one after the other and passed for the wrong reason.
- **An interrupted reopen left a channel held by nothing**, `PENDING` forever, with no way to
  recover because the registry still had it.
- **A probe in flight overwrote the cache reset.**

Redesigned: one `Observation` object per channel (job, health, digest) in one map under one lock;
the successor takes the channel's place first; the old job is cancelled and joined with a bounded
patience; the successor starts in a `finally` unless a stop removed it meanwhile. The second pass
held every concurrency invariant over 520 rounds and found three behaviours instead — a grading
pinned by the closed socket (told `connectionLost` now, once the close is confirmed), a
`runCatching` swallowing the caller's cancellation, and cache invalidation tied to "reopened" when
a self-reconnected socket is "changed" and not reopened. Each became a test.

## Measured live, before merge

Container built from the branch, never restarted: junk written into the file in place at
12:33:49 → the same heartbeat logged *credential changed — reopening*, answered `expired`; the
original bytes restored in place → the next heartbeat reopened again, `alive`, `live` three seconds
later. The owner's submit at 10:52:44 on the first cut had already been recorded through a
reopened socket; the final cut was proven the same way. PR #335 merged at 12:03 with CI 7/7.

## The owner's question that became #332

*"Couldn't it detect the changed cookie and switch by itself, so nothing goes in the file?"* Yes:
the browser is the only party that ever holds a fresh cookie, and the extension already talks to
the server every thirty seconds under a token. Two triggers, one endpoint, the file kept as a
server-written cache and the manual paste as the fallback §9.2 requires. The compose secret mount
turned out to be read-only (`rw=false`), which is where the file had to move. Built the same
afternoon; trigger 2 measured at 12:33 by the same junk-in-the-file method — the extension's next
heartbeat got `expired`, read the browser's cookie, posted it, the file was rewritten with a value
*different from the owner's paste* (the browser held a fresher one from a re-login), and `/watch`
answered `alive`. One second end to end.

## What was wrong that was mine

- "Heals without a restart" had been shipped as a promise for the observation without being
  measured for it.
- The first race test did not race.
- The first cut's cache reset was on the wrong axis ("did I reopen" rather than "did the
  credential change").
- I read a `/watch` answer from the wrong repository's remote and called the push proof
  inconclusive before noticing the command had run in the wrong checkout.
