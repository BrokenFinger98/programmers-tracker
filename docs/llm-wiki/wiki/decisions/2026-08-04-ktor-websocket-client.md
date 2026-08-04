---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [actioncable, websocket, library-choice]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# WebSocket client library = Ktor client (CIO engine)

Date: 2026-08-04 · Status: accepted · Issue: #6

## Context

Step 3 of the ActionCable client needs a WebSocket library to hold a long-lived
`wss://` connection to the Programmers cable (protocol doc §1) with custom headers
(Cookie, Origin, `Sec-WebSocket-Protocol: actioncable-v1-json`, §10) and expose
received frames as a coroutine `Flow`. The architecture decision "Async = Coroutines"
(CLAUDE.md) is already fixed, and observed sessions must survive a ~87 s timeout
grading run without the client cutting the request off (§13.5).

## Options considered

- **A. Ktor client (CIO + websockets plugin)** — coroutine-native: `webSocket {}` is a
  suspend block and `incoming` is a `ReceiveChannel`, so bridging to `Flow` is direct
  with no callback adapters. Header control is trivial. Cost: three new dependencies
  from a second framework family next to Spring.
- **B. OkHttp** — battle-tested, single small dependency, but its `WebSocketListener`
  is callback-based; every event would need a `callbackFlow` bridge plus manual
  backpressure care, i.e. hand-written glue exactly where bugs hide.
- **C. Spring/Jakarta WebSocket client (already on the classpath)** — no new
  dependency, but the `jakarta.websocket` API is annotation/listener-driven, makes
  custom handshake headers awkward (`Configurator` subclassing), and is blocking;
  it fights both the coroutine decision and the no-Spring-in-layer-tests ADR
  [[decisions/2026-08-04-test-environment]].

## Decision

Ktor client 3.5.2 with the CIO engine and the websockets plugin. The Ktor types are
confined to `ActionCableClient`; everything above it talks to a one-method-pair
`RawSocket` seam so routing logic stays testable against measured captures. The CIO
`requestTimeout` is disabled because the observation socket is long-lived and a
timeout verdict alone takes ~87 s (protocol doc §13.5).

## Rationale

- Coroutine-native receive loop matches the fixed "Coroutines, not threads"
  architecture decision with zero adapter code — the fewest places to be wrong.
- Measured header requirements (§10) are plain `header()` calls at connect time.
- Kotlin-first API keeps the client readable as a protocol artifact; this repository
  is also a portfolio.

## Accepted costs

- Three extra dependencies and a second HTTP-client family alongside Spring's.
  If HTTP fetches (problem page, catalog) later reuse the Ktor client, the cost
  amortizes; if they use Spring's `RestClient`, we carry both.
- CIO engine behavior (e.g. `requestTimeout`) is Ktor-specific knowledge future
  maintainers must hold.
- Ktor major upgrades become a protocol-layer concern.

## Outcome

Implemented in `protocol/ActionCableClient.kt` behind the `RawSocket` seam;
subscribe-frame construction and envelope routing are fixture-tested. Live
verification against the real cable is pending the human-run `liveObserve` check.
