---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, wiki, consolidation]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# Decision records: wiki ADRs are the single authority

## Context
Decisions were being written in two places: `.harness/state/decisions.md` (summary append)
and `wiki/decisions/` (detailed ADRs). Measured on day one of operation: 6 entries in state
vs 5 in the wiki — **they diverged within a single day** (the "two repos" decision was
missing from the wiki). Two records that disagree make it impossible to know which is true.

## Options considered
- **A. Keep both tiers** — state as a summary index, wiki as detail (original design)
- **B. Retire decisions.md** — wiki ADRs are the sole authority
- **C. Retire wiki decision pages** — state file only

## Decision
**B.** Delete `.harness/state/decisions.md` and keep only `docs/llm-wiki/wiki/decisions/`
(one decision per file). The session-start summary scan is replaced by the Decisions section
of `index.md` (one line per decision) — a file ingest maintains anyway, so there is no
synchronization burden.

## Rationale
Double-writing always diverges (measured on day one). This generalizes the existing
principle of keeping protocol facts only in `docs/programmers-protocol.md` (wiki schema
§5.1): **one fact in one place; everything else references it.**
C was rejected because it abandons the wiki's dual purpose (development memory +
portfolio, schema §0).

The state files' role is redefined — **state = position** (goal · progress: how far we
have come), **wiki = knowledge** (what was decided and why).

## Accepted costs
- At session start, only decision titles are scanned rather than full texts. If a conflict
  is suspected, the ADR must be opened one extra time (one added step)
- Writing an ADR is heavier than appending one line → the gate checks existence only,
  review owns quality, and starting with a stub ADR is allowed

## Outcome
_Update after implementation — decisions.md deleted after a parity check (5 entries confirmed as a superset)._
