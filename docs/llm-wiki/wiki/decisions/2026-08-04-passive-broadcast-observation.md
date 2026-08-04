---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [architecture, actioncable]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Judging integration = passive broadcast observation

## Context
Programmers judging results must be recorded locally. There is no submission-history API,
so **anything not captured the moment it happens is lost forever**.

## Options considered
- **A. Browser automation** — Claude drives Chrome to submit and collect results
- **B. Active server submission** — the server submits local code directly over WebSocket
- **C. MITM proxy** — decrypt and observe browser traffic
- **D. Extension traffic hooking** — a content script intercepts the WebSocket
- **E. Passive broadcast observation** — the server subscribes to the same channel and only listens

## Decision
**E.** The server sends nothing to Programmers; it only subscribes.

## Rationale
Measured confirmation that ActionCable streams are scoped by channel parameters, not by
connection ([[concepts/actioncable-broadcast-observation]]). A separate process connected
with only the cookie and received the same 4 messages the browser-fired run produced.

**With nothing to intercept**, C and D become unnecessary. A requires an AI session per
submission, and B does not fit the workflow of solving on the web.

## Accepted costs
- A **sensor extension** is needed to announce which problem was opened (no wildcard subscription)
- If the server is down, that submission is missed (unrecoverable)
- Code does not ride along with results, so the problem page must be fetched separately

## Outcome
_Update after implementation_
