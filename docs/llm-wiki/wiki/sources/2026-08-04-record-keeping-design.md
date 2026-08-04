---
type: source
project: programmers-tracker
tags: [record-keeping, wiki, git-hooks]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# 2026-08-04 Record-Keeping Design Session Summary

## Key claims
1. auto-compact does not delete the on-disk transcript — what gets lost is not the original but the **distillation**.
2. Nudges alone do not produce distillation (measured: global inbox 75 items / 2.4 GB per month, 0 ingests).
3. Dual records diverge within a day (measured: 6 state entries vs 5 wiki ADRs).
4. Hooks merge across settings layers — a project cannot disable a global hook.
5. The global archive hook fires regardless of cwd — raw still accumulates on the personal PC even with the project wiki in the repo (demonstrated).

## Pages this source updated
[[decisions/2026-08-04-two-public-repos]] ·
[[decisions/2026-08-04-decisions-live-in-wiki]] ·
[[decisions/2026-08-04-wiki-push-gate]] ·
[[decisions/2026-08-04-global-project-wiki-split]]
