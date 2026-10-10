---
type: decision
project: programmers-tracker
tags: [mcp, protocol-versioning, adapter, error-handling, logging]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-10
sources: [raw/sessions/2026-10-07-repairs-not-verdicts.md, entities/claude-code-mcp-client, raw/sessions/2026-10-08-seventeen-prs-through-one-queue.md]
---

# A fault of ours answers its call: its id, `-32603`, HTTP 200 in both eras

Date: 2026-10-08 · Status: accepted · Issue: #355

## Context

A tool call can fail with something that is not an `McpFailure`: a fault of ours, such as
`repair_steps` reporting a broken domain invariant as an `IllegalStateException`. `McpDispatcher`
rethrew it, and `McpController` answered `{"jsonrpc":"2.0","id":null,"error":{"code":-32603,…}}` on
HTTP 500, in both eras. The quality review of #353 found this. That branch pinned the shape in
`McpControllerTest` and filed #355 rather than fixing it
([[decisions/2026-10-07-repair-steps-are-served-not-judged]];
raw/sessions/2026-10-07-repairs-not-verdicts.md).

Two parts of that answer broke rules the project had already written:

- **`id: null`.** The request's id had been read. JSON-RPC 2.0 §5 asks for it to be echoed, and so
  does MCP `2026-07-28`: "Error responses MUST include the same ID as the request they correspond to
  (except in error cases where the ID could not be read due a malformed request)."
- **HTTP 500 in the handshake era.** [[decisions/2026-08-06-mcp-read-slice]] decision 3 keeps every
  handshake-era failure on 200, because a handshake client reads a non-2xx as a transport fault.

The modern status was the open question. Decision 3 says modern failures "carry the status the
binding assigns". The #353 test said "500 is what the binding assigns an internal error", but no one
had checked that against the binding.

## What the binding says

Read on 2026-10-08 from modelcontextprotocol.io: `specification/2026-07-28/basic/transports/streamable-http`
and `specification/2026-07-28/basic/index`.

- The binding assigns a status to **named errors only**. `400` goes with `-32020` HeaderMismatch,
  `-32021` MissingRequiredClientCapability, `-32022` UnsupportedProtocolVersion, and `-32602` for a
  request missing a required `_meta` field. `404` goes with `-32601` for an unimplemented method.
  `403` is for a bad `Origin`, `405` for GET or DELETE, and `202` for an accepted notification.
- **Nothing is assigned to `-32603`.** A request is answered with "a single JSON object" or an SSE
  stream, and the message-flow diagram shows `200 OK, application/json — JSON-RPC response`.
- The fallback rules tell a client to read the body of a 400, 404 or 405. A 500 is not on that list.

So the binding never said 500.

## Options considered

- **500 in the modern era, 200 in the handshake era.** This is HTTP's own meaning: the server failed.
  Rejected, for two reasons:
  - The binding does not ask for it.
  - The owner's client would not read it. Claude Code 2.1.285 reads a non-2xx answer as a JSON-RPC
    error only when it is a 400 that echoes the request id. It treats anything else as a transport
    fault ([[entities/claude-code-mcp-client]], learned by reading its code). All 27 recorded
    connections to the tracker were modern.

  On a 500, the `-32603` and the id this fix exists to echo would never reach the model. That is the
  same reason decision 3 gives for the handshake era.
- **400.** This is the status the client does read. Rejected: 400 says the request was wrong. The
  binding's era-detection rule tells a client that gets a recognised modern error on a 400 to correct
  its request, and nothing about the request was wrong.
- **200 in both eras.** Chosen.

## Decision

- `McpDispatcher` answers a fault of ours itself, to the call that met it. The answer is
  `-32603` "the server failed to handle the request", with `call.id`, on HTTP 200 in both eras.
  `McpFailure.internal()` carries 200.
- Throwables are converted in one place, `McpFailure.from(thrown)`:
  - an `McpFailure` passes through unchanged;
  - anything else is logged once at ERROR by its class (`An MCP request failed:
    IllegalStateException`) and becomes `internal()`.

  The dispatcher uses it, and so does the controller's backstop for anything the dispatcher did not
  answer.
- **An unreadable id stays `null`.** A body that does not parse is refused by the controller before
  any call exists, unchanged: `400`, `-32700`, `id: null`. JSON-RPC prescribes null when the id
  cannot be determined.
- The exception's text is never sent, neither on the wire nor in the log.

## Rationale

- **The id** ties an error to the call it answers. The MCP text above requires it whenever the id
  could be read.
- **The status** is the read-slice rule taken at its word: "the status the binding assigns". The
  binding assigns `-32603` none, so the error goes out on the status every other JSON-RPC response
  uses. The status also has to be one the client reads. Otherwise the id is echoed to nobody.
- **The log.** The controller used to log the whole throwable. That message is free text from
  wherever the fault was raised. The `ourFault` wrapper copies the invariant's own message into it,
  and an exception from a store or a decoder can name what it read, which is solving history. The
  class is enough to show that a fault happened. Readers under `problems/` already log a refusal's
  cause by class name for the same reason
  ([[decisions/2026-10-07-no-reader-follows-a-link-out-of-problems]]).

## Accepted costs

- **No fault of ours shows as a 5xx.** An access log, a proxy or a status-code dashboard would record
  a success. The server binds to loopback by default and has none of these, so the ERROR line is the
  one place a fault appears.
- **The log names the class and nothing else**: no message and no stack trace. To diagnose a fault,
  reproduce it from the call, which the client's transcript holds.
- **A client that retries on 5xx will not retry.** A fault here is an invariant breaking over the same
  records, so a retry would fail the same way.
- **The controller's backstop answers `id: null`** for a throwable that gets past the dispatcher.
  Nothing reaches it today. The origin check, the token check and body parsing throw only
  `McpFailure` or `UnauthorizedWatchException`, and the dispatcher catches everything else.

## Outcome

Branch `fix/355-internal-fault-keeps-id`.

**Tests.** Every new assertion failed against the old code first.

- `McpDispatcherTest`, three new tests. A code store throws an `IllegalStateException` whose message
  carries a marker; the tests cover the handshake answer, the modern answer, and the ERROR line by
  class. Before the fix, all three failed because the exception escaped `dispatch`.
- `McpControllerTest`, the #353 tests. They now assert the id, 200, `-32603` and no exception text,
  and a handshake-era twin was added. Before the fix, all three failed with `expected:<41|42|43> but
  was:<null>`. The baseline run had pinned 500.
- The parse-failure test now also asserts `id: null`. That pins existing behaviour.

**Mutation.** Eight mutants, all killed:

| Mutant | Tests that failed |
|---|---|
| a fault answered without the id | 5 |
| the fault rethrown as before | 6 |
| `internal()` on 500 | 3, the modern ones |
| 500 for a fault in both eras | 5 |
| the exception's message as the error | 5 |
| the throwable attached to the log line | 1 |
| no log line | 1 |
| a fabricated id for an unreadable one | 1 |

**Live: pending.** No real records break an invariant, so a running server cannot be made to fault on
demand. That Claude Code reads a `-32603` on 200 as the answer to its call is inferred from its code,
not observed.
