---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [architecture, hexagonal, ports-adapters, functional-core, ddd]
created: 2026-08-05
updated: 2026-08-11
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md, raw/sessions/2026-08-05-capture-pipeline-built-end-to-end.md]
---

# Architecture: hexagonal (orthodox-hybrid ports) + Functional Core, DDD tactical only

Date: 2026-08-05 · Status: accepted · Issue: #6 (discussion)

## Context

`development-rules.md` §1 fixed a package layout (`protocol / domain / application /
adapter`, "domain imports nothing") without naming the architecture style or stating a
port rule. The dominant structural risk in this system is **protocol contamination**:
if the camelCase/snake_case asymmetry (`testcaseId` vs `testcase_id`) reaches the
domain, every downstream consumer branches on problem type forever, and a Programmers
protocol change fans out across all layers.

Premise correction recorded here: the developer is **employed** (preparing
experienced-hire moves), not a job-seeker as the constitution's Role section still
says. Engineering-rigor tradeoffs are therefore argued on merit, not on time poverty;
the constitution text is amended in the follow-up issue.

## Options considered

- **Plain 3-tier (controller → service → repository)** — fewest files, but layers cut
  by technical role provide **no structural barrier** against protocol DTOs flowing
  into the domain; discipline would be the only defense.
- **Full DDD (strategic + tactical)** — strategic DDD solves inter-context problems.
  This system has one bounded context (learning records) and one developer; there is
  no problem for it to solve, only ceremony.
- **Orthodox hexagonal (interfaces on every boundary, both directions)** — uniform
  rules, but inbound use-case ports with exactly one implementation and one consumer
  are indirection without a swap or polymorphism story.
- **Lightweight hexagonal (interfaces only where 2+ impls or a test seam)** — cheapest,
  but leaves inbound boundaries irregular ("why does this edge have no port?").
- **Orthodox-hybrid (chosen)** — orthodox on the outbound side, selective inbound.

## Decision

Hexagonal architecture with these port rules:

1. **Outbound (driven) dependencies are always ports** — `RecordStore`, `GitSync`,
   `CodeFetcher`, `CatalogFetcher`, `SessionProvider`, `RawSocket`. Integration tests
   are cookie-gated and skip by default, so every one of these needs a test double
   anyway; the rule costs nothing extra.
2. **Inbound (driving) use-case interfaces exist only where two consumers share the
   use case** (web + MCP). A use case with a single consumer is a plain application
   service.
3. **DTO translation is mandatory at the protocol boundary** (`protocol/parse` is the
   only place protocol names may touch domain types); the web boundary translates
   pragmatically.
4. **Functional Core**: verdict/confidence/aggregation logic lives in pure calculators
   (`domain/calc`) with no I/O knowledge.
5. **DDD tactical patterns only**: value objects, static factories, behavior methods.
   Aggregates, repository-pattern ceremony, and strategic design are rejected.

## Rationale

- The #1 risk is a **dependency-direction problem**, and hexagonal is the only
  candidate that turns "protocol must not leak" from a review rule into a compile
  error (`domain` cannot import `protocol`).
- The shape had already emerged unprompted during implementation — `SessionProvider`
  with two implementations, the `RawSocket` seam isolating Ktor, web and MCP heading
  toward shared application services — strong evidence the pattern fits the problem.
- Functional Core protects the system's actual asset: verdict correctness ("silently
  accumulating wrong data is the worst outcome"), exhaustively unit-testable with
  zero mocks, shared between live capture and historical re-analysis so the two can
  never drift.
- Value objects are justified by a measured incident: swapping `challengeableId` and
  the codes key caused four consecutive failures during reverse engineering (protocol
  doc ch. 3). Distinct value classes make that a compile error.
- Every port must have an articulable reason to exist ("two implementations" /
  "two consumers" / "test seam"), which is a stronger position than uniform ceremony.

## Accepted costs

- Mixed inbound rule means occasional judgment calls about when a second consumer is
  "real enough" to introduce a use-case port.
- Mapping code at the protocol boundary is mandatory even when shapes look similar —
  that is the point, but it is extra code.
- Some outbound ports will have one production implementation for a long time.

## Outcome

Recorded 2026-08-05. `development-rules.md` §1 amended in the same branch to name the
style and state the port rules. Applies from implementation-order item 3 onward; the
existing `protocol` package already conforms.
