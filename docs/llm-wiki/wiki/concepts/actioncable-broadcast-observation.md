---
type: concept
project: programmers-tracker
tags: [actioncable, architecture, websocket]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
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

## Result messages carry no code

Broadcasts contain no source code. Instead, fetching the problem page while logged in
returns the user's last saved code (`<input data-type="code" value="...">`).

→ [[decisions/2026-08-04-passive-broadcast-observation]]
