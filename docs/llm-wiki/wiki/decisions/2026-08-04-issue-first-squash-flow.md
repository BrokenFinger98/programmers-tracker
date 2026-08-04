---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, git-flow, open-source]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-oss-workflow.md]
---

# Issue-first flow, squash-only merges

## Context
Work landed on `main` directly (initial docs) or via ad-hoc branches. Going
open-source means outside contributors need one predictable flow, and the owner
already runs issue→branch→PR→squash everywhere else.

## Options considered
- **A. Keep ad-hoc** — no server enforcement, drift guaranteed
- **B. Flow by convention only** — docs say it, nothing enforces it
- **C. Flow + server enforcement + skills** — branch protection (PR required,
  admins included), squash-only with auto-delete, and project skills
  /issue /commit /pull-request encoding the repo's gates

## Decision
**C.** Branch names `<type>/<issue#>-<slug>`; every merge is a squash; the
owner's GitLab skills stay untouched globally and are shadowed in-repo by
GitHub-adapted project skills.

## Rationale
Server-side protection is the only mechanism that binds strangers and the owner
equally (`enforce_admins: true` — dogfooding). Skills make the cheap path the
correct path: gates (protocol evidence, TDD pairing, wiki gate) live inside
/commit and /pull-request instead of relying on memory.

## Accepted costs
- No emergency direct push — toggling protection off is the documented escape
- Same-name skill shadowing (issue/commit) relies on directory-scope resolution
- CI status checks deferred with CI itself (Phase 1) — until then, protection
  requires only the PR shape, not green checks

## Outcome
_This decision's own PR is the first end-to-end run of the flow._
