---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [mcp, protocol-versioning, adapter, authorization, dependencies]
created: 2026-08-06
updated: 2026-08-06
sources: []
---

# MCP read slice: hand-rolled JSON-RPC over MVC, dual-era, three tools

Date: 2026-08-06 · Status: accepted · Issue: #46

## Context

The README's pitch is "capture your solving history and expose it to an AI". The capture
half works end to end; the expose half had **no code at all** — no `adapter/mcp` package
and no MCP dependency anywhere. Design §7 specified the interface and nothing implemented
it.

Three decisions had to be made rather than defaulted into.

## Decision 1 — three tools, and the other seventeen absent rather than stubbed

Design §7 lists roughly twenty tools across five groups. Most of them need something that
does not exist: the 689-problem catalog snapshot, the solved.ac tag vocabulary, exam state,
review scheduling. Only three can be answered from what is on disk today —
`log/submissions.jsonl`:

- `submissions(since?, verdict?)`
- `get_problem(lessonId)`
- `stats(groupBy)` — `verdict` · `language` · `problem`

**Nothing else is stubbed.** A tool that exists and returns "not implemented" is worse than
an absent one, because a client discovers it through `tools/list` and plans around it — the
model spends a turn calling it and a turn recovering, and the user reads a capability list
that is a lie. `docs/mcp.md` names the seventeen explicitly so their absence is stated
rather than merely true.

`get_problem` answers from the submission log alone, not from a scan of
`problems/<id>-<slug>/attempts/`. The log is already declared the single authority for
attempt numbers ([[decisions/2026-08-05-write-serialization]]), and a directory scan would
be a second source of the same fact. Each record carries its own `rawPath` and `codePath`,
so the paths are reported as recorded.

## Decision 2 — hand-rolled JSON-RPC, not Spring AI's MCP server starter

Both were defensible and the choice was made by measurement, not by release notes — this
project has already been bitten by a BOM silently pinning a transitive version
([[concepts/bom-version-shadowing]]), which cost a live capture.

**What was measured**, by adding `org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.0`
to this build and resolving `runtimeClasspath`:

- It resolves cleanly. Its POM pins `spring-boot-starter-web:4.1.0` — our exact version. So
  Boot 4.1 compatibility is **real**, not the blocker one might assume.
- It brings roughly thirty artifacts, including `io.projectreactor:reactor-core`, the
  victools JSON-schema generator, the networknt schema validator, `spring-ai-model` and
  `spring-ai-template-st`, and Jackson 3 (`tools.jackson`) alongside Jackson 2.
- **Decisively:** `io.modelcontextprotocol.sdk:mcp-core:2.0.0` declares
  `MCP_2024_11_05`, `MCP_2025_03_26`, `MCP_2025_06_18`, `MCP_2025_11_25` — and nothing
  else. The SDK does **not** implement the current revision `2026-07-28` (read from the
  jar's `ProtocolVersions.class`).

That last point dissolves the strongest argument for the starter. "Use the library so the
protocol is somebody else's problem" is false when the library is a revision behind: we
would still have to write the modern era ourselves, on top of a dependency tree that
contradicts two standing decisions (Reactor, where
[[decisions/2026-08-05-backend-stack]] chose virtual threads inbound and coroutines
outbound; Jackson, where the project serializes with kotlinx.serialization).

Against that, the surface actually needed is small: one POST endpoint, a JSON-RPC envelope,
five methods and three tools, with no SSE (nothing streams progress), no resources, no
prompts, no sampling. That is what was built, in `adapter/mcp`, using the
kotlinx.serialization already present. **Zero new dependencies.**

This is a reversible decision. If the SDK reaches `2026-07-28` and the tool set grows past
what a hand-rolled dispatcher wants to carry, adopting it is a contained change: the tools
themselves are already independent of the transport.

## Decision 3 — dual-era, because the protocol moved under us

MCP is dated and versioned, and the current revision is **`2026-07-28`**, read from the
specification on 2026-08-06 rather than from memory. It is not a point release:

- `initialize` / `notifications/initialized` **removed**; MCP is now stateless
- protocol-level sessions and the `Mcp-Session-Id` header **removed**
- the standalone `GET` SSE stream and `Last-Event-ID` resumability **removed**
- `ping` **removed**
- `server/discover` added, and servers **MUST** implement it
- every request carries `io.modelcontextprotocol/protocolVersion` and
  `io.modelcontextprotocol/clientCapabilities` in `_meta`; both are required
- every result carries `resultType`; list results carry `ttlMs` and `cacheScope`
- `Mcp-Method` and `Mcp-Name` headers are required and **must agree with the body**
  (`-32020 HeaderMismatch`)
- the server-error range was partitioned: `-32000..-32019` is closed to new allocations,
  `-32020..-32099` belongs to the specification

The specification itself names the two halves *modern* and *legacy* and defines a
**dual-era** server. Its compatibility matrix decides the question:

- modern-only → **fails** with every client shipping today
- legacy-only → fails with modern-only clients, and is a revision behind on arrival

So the server answers both. `2026-07-28` for modern clients; `2025-11-25` and `2025-06-18`
for handshake clients (`2025-03-26` is excluded — it predates `structuredContent`, which
every tool here returns). The era is decided per request by whether `_meta` carries a
protocol version, which is exactly what the spec says a dual-era server should key on, and
means nothing is remembered between requests.

One consequence worth writing down: **a modern `UnsupportedProtocolVersionError` advertises
only `2026-07-28`**, not the legacy revisions. The legacy ones are reachable through
`initialize` and nothing else, so listing them would invite a retry that could only fail
again.

Handshake-era failures are answered on **HTTP 200** with the JSON-RPC error in the body,
while modern failures carry the status the binding assigns (`404` unknown method, `400` bad
version or header mismatch). A handshake-era client reads a non-2xx as a transport fault and
never surfaces the error written for it.

## Authorization — the same token as `/watch`, plus Origin

Reused deliberately rather than by omission. `/watch` needs a token because loopback is
shared with every process on the machine; this endpoint answers with the user's **entire
solving history**, so the bar cannot be lower. One process holds one credential — a second
token would double what the user must copy and protect nothing extra, since both guard the
same process.

Added on top, because the MCP specification makes it a **MUST**: `Origin` validation. The
allowlist is empty by default, so **any** request carrying an `Origin` is refused with
`403`. A native MCP client sends none; one that does is a browser, and a browser has no
business reading a solving history. This is defence in depth — the token is what actually
stops a rebound page, since a cross-origin request cannot set `X-Tracker-Token` without a
preflight we never approve.

**Accepted cost:** the class is still named `WatchToken` and the property is still
`tracker.watch.token`, which are now narrower than their role. Renaming would touch six
files unrelated to this slice, and CLAUDE.md forbids mixing unrelated refactoring into one
PR. It is naming debt, recorded here rather than left to be rediscovered.

## Missing data must look missing

The tools omit a field that was never recorded rather than filling it in — no blank title,
no zero score, no bucket named "unknown". A `stats` bucket with no `key` is the count of
submissions whose grouping value was never recorded, and the keyless bucket sorts **last
whatever its size**, so a reader taking the first entry as the headline is never handed
"nothing" as the most common verdict. This is the failure recorded in
[[concepts/assumption-vs-measurement]], applied before it could happen again.

## Consequences

- `domain/calc` gained two pure calculators; `SubmissionTally` returns `TallyBucket`, which
  is deliberately **not** `@Serializable` and has no default arguments. The wire shape is
  assembled in the adapter that owns it. This also removed the only branches the 95%
  `domain/calc` coverage gate could not reach, which were compiler-generated constructor
  branches rather than decision logic — the gate measuring the wrong thing, in the spirit of
  [[decisions/2026-08-05-ci-guard-scoping]].
- `RecordQuery` is read-only by construction: no `RecordWriter` and no `GitSync` are in
  reach, so a prompt-injected instruction to alter records has no path to act on.
- **Known gap:** a log line the store cannot parse at all is dropped with a server-side
  warning and is **invisible to the MCP client**. Reporting a count was considered and
  rejected: `RecordStore.read()` already drops such lines before `RecordQuery` sees them, so
  any count assembled above it would under-report — and a number that reads like a
  measurement but is not one is precisely
  [[concepts/assumption-vs-measurement]]. Closing it needs a `RecordStore` port change,
  which is outside this slice.
- **Known gap:** no pagination. `submissions` with no arguments returns the whole log.
  Bounded scope was explicitly requested for this issue; a `limit` argument is the obvious
  next step and was not added unilaterally.
