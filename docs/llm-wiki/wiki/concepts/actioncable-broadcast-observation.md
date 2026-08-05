---
type: concept
project: programmers-tracker
tags: [actioncable, architecture, websocket]
created: 2026-08-04
updated: 2026-08-05
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md, raw/sessions/2026-08-05-design-review-and-stack-upgrade.md]
---

# ActionCable Passive Broadcast Observation

## How it works

Rails ActionCable streams are **scoped by channel parameters, not by connection**.
Every client subscribed to the same `identifier` receives the same messages simultaneously.

So when the user clicks "judge" in the browser, **the results also flow into any other
process subscribed to the same channel.** There is nothing to intercept, so neither an
MITM proxy nor extension hooking is needed.

## Measured (2026-08-04)

A separate Python process connected with only the session cookie → subscribed → `run` fired from the browser:

```
[0.43s] CONFIRM_SUBSCRIPTION     ← a non-browser process also passes auth
[12.98s] BROADCAST run/start
[13.99s] BROADCAST run/testcase
[14.07s] BROADCAST run/testcase
[14.07s] BROADCAST run/result
```

Exactly matches the 4 messages the browser received.

## Limitation — no wildcards

The `identifier` must match character-for-character; there is no pattern subscription.
There is also no way to subscribe to "all submissions by this user." Therefore **the server
must know in advance which problem the user opened.** With 689 problems × 13 languages,
subscribing to everything is unrealistic — which is why a sensor is needed.

## Reproduced from Kotlin (2026-08-05)

The Python measurement above was reproduced by the project's own Kotlin client, first on
Spring Boot 3.5 / JVM 21 and again after the Boot 4.1 / JVM 25 upgrade:

```
[0.40s confirmed]   confirm_subscription
[1799.82s broadcast] run/start
[1800.73s broadcast] run/testcase index 1     ← index 1 arrives BEFORE index 0
[1800.76s broadcast] run/testcase index 0
[1800.77s broadcast] run/result 2/2
```

Out-of-order testcases are therefore not a quirk of one measurement — they are the norm,
because grading is parallel. Sorting alone is not enough: a slow case would simply be missing,
so the expected count must be checked ([[decisions/2026-08-05-failure-taxonomy]]).

## Observation — the socket does not stay open (2026-08-05, cause unestablished)

An idle observation run ended after **~30 m 50 s**: no exception, no close frame logged, no
reconnect. The frame `Flow` completed and the process exited 0.

The cause was **not** established — server idle timeout, NAT, Wi-Fi and machine sleep are all
candidates — so this is **not** recorded in `docs/programmers-protocol.md`, which holds only
measured protocol facts. Treat it as an observation awaiting a second, independent
reproduction.

What it does establish is a defect in our own client: a session can end with **zero signal**.
Everything broadcast afterwards is lost forever (protocol §11) and nothing in the logs would
say a gap existed. This is why liveness detection from the 3-second `ping` and a loud gap log
are load-bearing rather than precautionary.

## Result messages carry no code

Broadcasts contain no source code. Instead, fetching the problem page while logged in
returns the user's last saved code (`<input data-type="code" value="...">`).

→ [[decisions/2026-08-04-passive-broadcast-observation]]
