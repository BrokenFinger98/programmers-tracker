---
type: synthesis
project: programmers-tracker
tags: [protocol, reverse-engineering, actioncable]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# The Full Story of Uncovering the Programmers Protocol

> The single source of truth for the facts is `docs/programmers-protocol.md`. This page records **how we found them out**.

## Why it was needed

Programmers streams judging results past you and then discards them. There is no
submission-history API. [[entities/baekjoonhub]] also only works on correct answers, so
failures structurally leave no record. The 2025 history — **449 total attempts / 43
problems solved** — means 406 failures are already lost.

## The trail

No published account existed anywhere on GitHub, so it had to be pioneered directly.

1. **Reading the bundle** — found `channel.perform("submit", {codes})` in `application.js`.
   The button was a shell; the real thing was WebSocket.
2. **Getting the address** — `<meta name="action-cable-url">` → `wss://ws.programmers.co.kr:443/cable`
3. **Measuring the handshake** — `101 Switching Protocols` + `{"type":"welcome"}`
4. **Subscription confirmed** — `confirm_subscription`
5. **End to end** — a real submission passed with 100 points, rating 1371 → 1372

Reading the bundle was decisive. The network tab alone would not have revealed the action
names and payload structure inside the WebSocket frames. **Back-tracking from handler
names (`handleSubmit`) in minified JS yields the full set of message types the server can
send.**

## Where we got stuck

See [[concepts/verdict-classification]]. In short: we confused `challengeable_id` with the
codes key and failed 4 times in a row, and the symptom — "16/16 passed but nothing
recorded" — made it hard to trace. **The trap was that even with the wrong parameter, the
subscription is confirmed and judging still runs.**

Lesson: when dealing with an external protocol, *partial success* is not success. Verify to the end.

## Decisions derived

[[decisions/2026-08-04-passive-broadcast-observation]] ·
[[decisions/2026-08-04-solve-in-web-editor]]
