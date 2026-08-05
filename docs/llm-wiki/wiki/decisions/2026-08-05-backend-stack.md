---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [stack, jvm, spring-boot, coroutines, virtual-threads, webflux, versions]
created: 2026-08-05
updated: 2026-08-05
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Backend stack: JVM 25 · Spring Boot 4.x · MVC+VT inbound · coroutines+Ktor outbound

Date: 2026-08-05 · Status: accepted · Issue: #6 (discussion), implementation in a follow-up issue

## Context

Phase 1 scaffolding picked concrete versions (Spring Boot 3.5.16, JVM 21) by mechanically
following the constitution without a live discussion. Reviewing them surfaced two facts:

- **Spring Boot 3.5 reached open-source EOL on 2026-06-30**; 3.5.16 is the final OSS
  patch and every 3.x branch is now unsupported. A publicly distributed tool cannot sit
  on a line that will receive no security fixes. (Verified via web sources and Maven
  Central metadata, 2026-08-05; latest GA is 4.1.0.)
- The choice of async model had never been argued from the actual workload. A
  communication inventory (design doc §3.1, §4) shows: **inbound is 2 request/response
  endpoints** (`POST /watch` from the sensor extension, MCP Streamable HTTP from AI
  agents — single user, at most a handful of concurrent agents), and **the only
  long-lived stream is outbound**: the ActionCable observation socket (LRU 8
  subscriptions, sporadic frames, 87 s worst-case grading).

## Options considered

- **WebFlux for MCP Streamable HTTP** — rejected. The MCP Java SDK ships an MVC
  (servlet) transport as well; servlet-stack streaming responses cover Streamable HTTP.
  WebFlux pays off at thousands of concurrent streams; we have ~1–5. Cost would be
  `Mono`/`Flux` infecting every handler and a second reactive model next to coroutines.
- **Virtual threads everywhere (drop coroutines + Ktor)** — rejected. The "blocking
  style" promise breaks on the WebSocket client: JDK `HttpClient` and OkHttp expose
  callback APIs, so we would hand-write the callback→queue bridge that Ktor's suspend
  API eliminates. The existing coroutine client is already live-verified.
- **Coroutines everywhere (suspend MVC handlers)** — rejected. Inbound traffic is
  trivially request/response; suspend handlers add complexity with no benefit.
- **Ktor server / no framework** — rejected for the server. Spring stays for its
  config management (`@ConfigurationProperties` for public distribution), test
  infrastructure, the MCP SDK's first-class Spring MVC transport, and the project's
  explicit portfolio purpose. We accept that it is technically heavier than needed.
- **Jackson-only serialization** — rejected for the protocol package. Preserving raw
  frames as `JsonObject` inside sealed hierarchies and explicit lenient switches are
  exactly the kotlinx.serialization shape; Jackson remains the web layer's default.

## Decision

| Concern | Choice |
|---|---|
| JVM | **25** (current LTS; toolchain support verified at upgrade time) |
| Framework | **Spring Boot 4.x** (4.1.0 at decision time), Spring MVC |
| Inbound async (`/watch`, MCP) | **Virtual threads** on MVC |
| Outbound observation (ActionCable, CodeFetch, catalog) | **Coroutines + Ktor client** |
| Serialization | kotlinx.serialization in `protocol/`; Jackson in the web layer |
| Version policy | Pin latest stable of a **supported** line; verify EOL schedules and Maven Central `maven-metadata.xml` (search index lags); verify BOM-managed versions with `dependencyInsight` |

The constitution's stack table (3.x, JVM 21) is amended in the follow-up upgrade issue.

## Rationale

- The communication inventory shows the async need is **shaped differently per
  direction**: request/response inbound vs one long-lived outbound stream. Choosing a
  model per layer follows the workload instead of forcing one model everywhere.
- EOL evidence made 3.x untenable independent of preference.
- The version policy line exists because we were bitten twice in one day: the Maven
  Central search index reported stale latest versions, and Spring Boot's BOM silently
  downgraded `kotlinx-coroutines` to 1.8.1 under Ktor 3.5.2 (runtime
  `NoSuchMethodError` at first connect — see [[decisions/2026-08-04-ktor-websocket-client]]).

## Accepted costs

- Two async models in one codebase, bounded by layer (documented boundary: the single
  `/watch` → subscription-scope launch bridge).
- Two JSON libraries on the classpath (Jackson arrives with Spring regardless).
- Spring Boot 4 migration work plus re-verification of the live capture path.
- The coroutines BOM override must survive future Spring Boot upgrades.

## Outcome

Approved 2026-08-05 after a full-stack review, and **executed the same day** in #10:
Spring Boot 4.1.0, `jvmToolchain(25)` (verified by class-file major version 69), version
catalog at `gradle/libs.versions.toml`, and `spring.threads.virtual.enabled` for the
inbound side. Gates green (76 tests) and the live capture path re-verified end to end —
`confirm_subscription` plus a full browser-triggered run sequence on the new stack.

**The version-policy clause earned itself immediately.** Checking the 4.1.0 BOM before
trusting it showed it pins `kotlin-coroutines.version=1.10.2` — still below the 1.11.0
Ktor 3.5.2 requires — and `kotlin.version=2.3.21` against our 2.4.10 compiler plugin. The
`NoSuchMethodError` from #6 would have returned. Both overrides were kept and a second one
added; a version catalog does **not** control BOM-managed versions, so the two mechanisms
coexist and `dependencyInsight` is the only proof.

Docker-based startup and the bootstrap guide remain separate issues.
