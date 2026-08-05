---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [docker, security, network, bind-address, distribution]
created: 2026-08-06
updated: 2026-08-06
sources: [decisions/2026-08-05-backend-stack, decisions/2026-08-05-failure-taxonomy]
---

# Container network posture: the publish address is the control, not the bind address

Date: 2026-08-06 · Status: accepted · Issue: #43

## Context

`application.yml` binds `server.address` to `127.0.0.1` and says why: this process holds a
live Programmers session cookie in memory and can push to the user's GitHub, so `/watch`
must not be reachable from the LAN. That property is real and load-bearing.

Packaging the process as a container (#43) breaks the mechanism while leaving the property
intact, and the two are easy to confuse:

- A container has its **own network namespace**. `127.0.0.1` inside it is that namespace's
  loopback, not the host's.
- `docker run -p 8080:8080` forwards to the container's **eth0** address. It never
  forwards to the container's loopback.

So a container that faithfully keeps `server.address=127.0.0.1` is unreachable from the
host — including from the browser extension that is the only caller `/watch` has. The
image would boot, pass a health check, and be useless. Mechanically applying the rule
produces the opposite of the rule's purpose.

## Options considered

- **A — keep the loopback bind, make the user opt out.** The image default stays
  `127.0.0.1`; compose publishes to the host loopback only; the documented first-run
  step is "set `TRACKER_BIND_ADDRESS=0.0.0.0` in your `.env`". Rejected, and the reason
  is about people rather than packets: it puts `TRACKER_BIND_ADDRESS=0.0.0.0` on the
  happy path of the getting-started guide. Every user would type it once, and some
  would carry the habit to a native run, where it genuinely does offer `/watch` to the
  whole LAN. A safe-looking default that teaches a dangerous setting is worse than an
  unsafe-looking one that stays confined.
- **B — `network_mode: host`.** `127.0.0.1` then really is the host's loopback and the
  container is indistinguishable from a native run. Rejected because it degrades on
  exactly the platforms this has to support: on Docker Desktop the "host" is the Linux
  VM, host networking is behind a settings toggle, and the guarantee differs per
  platform. The three-OS CI matrix exists because this tool claims macOS and Windows.
- **C — bind `0.0.0.0` inside the namespace, publish to the host loopback only
  (chosen).**

## Decision

1. **`compose.yaml` sets `TRACKER_BIND_ADDRESS=0.0.0.0`, and publishes
   `127.0.0.1:${TRACKER_PORT}:${TRACKER_PORT}`.** The loopback prefix on the *publish*
   address is what keeps `/watch` off the LAN; the bind address inside the namespace is
   not that control and must not be mistaken for it.
2. **The application default is untouched.** `application.yml` still binds `127.0.0.1`,
   and the `Dockerfile` sets no bind address at all. A native run — the way a developer
   runs it, and the way `./gradlew bootRun` runs it — is loopback-bound as before. The
   `0.0.0.0` lives in one file, next to the loopback publish that makes it safe, and the
   two are never separated.
3. **A dedicated Docker network, and the `/watch` token as the second layer.** Anything
   sharing the container's network can reach it whatever the publish address says. The
   service gets its own network and nothing else joins it, and the token
   ([[decisions/2026-08-05-failure-taxonomy]] is where `/watch`'s authorization posture
   comes from) remains mandatory.
4. **CI asserts the distinction rather than describing it.** The Docker job starts the
   image twice — once at the default bind and once with `TRACKER_BIND_ADDRESS=0.0.0.0`,
   both published to `127.0.0.1` — and requires the first to be **unreachable** and the
   second reachable. If someone later "simplifies" the loopback bind out of
   `application.yml`, the first assertion fails.
5. **The guide states the general rule in one sentence**: a bind address inside a
   container namespace is not the same control as a bind address on a host. That sentence
   is the reusable part; the compose comment is only its local application.

## Rationale

- The property to preserve is "not reachable from the LAN", not "the string `127.0.0.1`
  appears in the config". Restating the property and re-deriving the mechanism is what
  keeps a rule from outliving its reason.
- Option A's flaw is not technical, it is that documentation teaches. The setting that is
  dangerous natively should never appear in a getting-started path, even when the
  container context makes that particular instance harmless.
- Decision 4 exists because comments do not fail builds. The nuance here is precisely the
  kind that a future reader "cleans up".

## Accepted costs

- `0.0.0.0` appears in a committed file, and anyone reading only that line will think the
  posture is worse than it is. Mitigated by the comment block and by this record, but the
  first impression is genuinely misleading.
- The residual exposure is real: containers sharing the network can reach `/watch`. A
  dedicated network makes that an explicit act rather than an accident, but it is not a
  boundary the tool enforces.
- Compose is now the only supported way to run the container correctly. A bare
  `docker run -p 8080:8080` with `TRACKER_BIND_ADDRESS=0.0.0.0` publishes to every host
  interface, and nothing in the image stops it.

## Outcome

Recorded 2026-08-06 with the image and the bootstrap guide (#43). Related:
[[decisions/2026-08-05-backend-stack]] · [[decisions/2026-08-05-write-serialization]].

**Adjacent finding, unresolved.** [[decisions/2026-08-05-write-serialization]] decision 5
says the record repository is locked exclusively at startup, and names "container plus a
local run" as the double-writer it exists for. That lock **is not implemented** — there is
no `FileLock` or any equivalent anywhere in `src/main/kotlin`. Shipping a container makes
the scenario the ADR predicted trivially reachable, so the gap is now larger than when it
was written. It is out of #43's scope and is recorded here so the next reader does not
infer from this file that containers are safe to double-run. Tracked as **#44**.
