---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [learning-design, MCP, debugging]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# AI debugger control not adopted

## Context
The question: "could debugging be built into the server with something like JetBrains MCP?"

## Options considered
- **A. Attach a Debugger MCP and let the AI debug** — the AI drives breakpoints · stepping · variable inspection
- **B. Do not attach it; the user debugs by hand**

## Decision
**B.**

## Rationale
It is technically feasible. The [Debugger MCP Server](https://plugins.jetbrains.com/plugin/29233-debugger-mcp-server)
exposes all of it — breakpoints, sessions, stepping, variable inspection, expression
evaluation — through 37 tools in 8 API groups.

But **the reason debugging was wanted in the first place was "I want to set the
breakpoints myself."** If the AI drives the debugger, debugging is not being learned —
it is being outsourced.

It also **directly contradicts the decision to turn off autocomplete.** Autocomplete
merely types a few characters for you, and that was turned off; handing the far more
essential skill of "how to narrow a problem down" to the AI points the opposite way.

Architecturally there is no reason to reimplement it either. MCP clients attach multiple
servers at once, so if it is ever needed it can be registered as a separate server.
**Small servers each exposing what they do best, composed by the client, is MCP's design
philosophy.**

## Accepted costs
- Complex bugs take longer to debug (an intended cost)

## Outcome
Not an irreversible decision. If it becomes necessary, attach it as a separate MCP server.
