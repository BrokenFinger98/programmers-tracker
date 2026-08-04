---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [repository, publishing-strategy]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md, raw/sessions/2026-08-04-record-keeping-design.md]
---

# Two repos · both public

## Context
Where and how to keep the code, design documents, and solving records. The records include
failure history and hint-usage stages, and the repositories double as a portfolio.

## Options considered
- **A. Single repo** — code and records in one place
- **B. 2 repos · both public** — `programmers-tracker` (code + design + wiki) / `ps-records` (records)
- **C. 2 repos · records private** — failure history kept private

## Decision
**B.**

## Rationale
Design documents separated from code always drift, so they stay in one repo. Records
differ from code in nature (personal data) and lifespan (per-account), so they are split
off. Keeping `ps-records` public reflects the judgment that **a growth narrative including
failure history and hint-usage stages is an asset**.

## Accepted costs
- The psychological cost of making even failures public
- Credential and personal-data handling must be a notch stricter — **no gitignore
  exceptions** (session cookies and emails are never committed under any circumstances,
  the same axis as the raw-text import ban in
  [[decisions/2026-08-04-global-project-wiki-split]])

## Outcome
Both repos created 2026-08-04. This decision existed only in `.harness/state/decisions.md`
before being migrated to an ADR per [[decisions/2026-08-04-decisions-live-in-wiki]] —
this very entry is the real-world case (6 vs 5) of dual records diverging on day one.
