---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [workflow, UX]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Code is written in the Programmers web editor

## Context
Starting from the requirement "write code by hand without autocomplete, and use the IDE
debugger when stuck," the initial design was **local editor + active server submission**.

## Options considered
- **A. Local-editor centric** — the server scaffolds problems and submits local files
- **B. Web-editor centric** — solve on Programmers as-is; the server only observes

## Decision
**B.** The user lives entirely on the Programmers web.

## Rationale
A would require **reimplementing problem browsing, search, and statement reading as a CLI** —
rebuilding, worse, what Programmers already does well.

More importantly, **real coding tests are taken in a web editor**. Since the goal was to
get used to an autocomplete-free environment, solving on the web actually serves the goal
better.

The debugging requirement is met by generating a `Solution.java` + `SolutionTest.java`
runner from the code the server captured. When stuck on the web, open it in IntelliJ and
set breakpoints.

## Accepted costs
- The local-code-writing path becomes secondary (the Judge component remains, so it can be restored later)
- Capture timing depends on user behavior

## Outcome
_Update after implementation_
