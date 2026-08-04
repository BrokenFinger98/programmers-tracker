---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, language, open-source]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-oss-workflow.md]
---

# All work artifacts in English

## Context
development-rules §12 said "write in Korean — the audience is Korean job-seekers."
The repo went public with external issues/PRs expected; Korean-only docs,
hook messages, and templates exclude every non-Korean contributor.

## Options considered
- **A. Korean-first, English on demand** — original §12
- **B. Bilingual docs** — every page twice; guaranteed drift (same failure
  mode as the retired decisions.md duplication)
- **C. English-only artifacts** — conversation language stays per-user

## Decision
**C.** Everything committed is English: docs, comments, commit messages,
wiki pages, user-facing hook output.

## Rationale
Open-source posture makes contributor-facing language a functional
requirement — the push gate's stderr is read by strangers. B recreates
the two-copies-diverge failure this project already paid for once.

## Accepted costs
- Korean readers (the original audience) lose first-language docs
- Mixed-language history: `raw/sessions/` and old `log.md` entries stay
  Korean — the wiki schema declares raw immutable, and history is evidence
- One-time ~4,400-line translation with drift risk on the protocol doc,
  mitigated by fence-byte-stability checks

## Outcome
_Update after the migration PR merges._
