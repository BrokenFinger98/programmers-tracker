---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [record-keeping, wiki, layering]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-record-keeping-design.md]
---

# Global and project wikis split into 3 layers

## Context
A personal global wiki (`~/Desktop/llm-wiki`) and this repo's wiki (`docs/llm-wiki/`)
coexist. The concern: "doesn't the wiki split in two, with project work no longer
accumulating in the personal wiki?"

## Options considered
- **A. Consolidate globally** — retire the project wiki, put everything in the global one
- **B. Consolidate in the project** — retire the global wiki
- **C. 3-layer split** — raw = personal PC (automatic) / 1st distillation = repo wiki / 2nd distillation = global wiki

## Decision
**C.** This is not duplication of the same content but placement at **different layers**:
raw (full text of every session) → 1st distillation (project decisions · concepts) →
2nd distillation (cross-project generalization).

## Rationale
Four reasons the project wiki must live inside the repo — ① portfolio (wiki schema §0
dual purpose), ② cloning brings knowledge + workflow together, ③ the push gate only works
this way (machine verification requires the same repo), ④ the knowledge has the same
lifespan as the code. A loses all four, and the global wiki mixes raw session text with
records from other projects, so **it cannot be a publication target** (structurally).

The "nothing accumulates on the personal PC" concern was measured to be false: the global
SessionEnd/PreCompact archive hooks are user-level and fire regardless of cwd. On
2026-08-04 we ran the hook against this session's actual transcript and confirmed a 551k
file landing in the global inbox. **Raw keeps accumulating globally.**

The principle is the same as [[decisions/2026-08-04-decisions-live-in-wiki]]: one fact in
one place; everything else references it.

## Accepted costs
- Finding project knowledge from the global side requires a registry (one page per project
  in the global wiki) — global wiki cleanup deferred as separate work
- **Bringing raw session text into the project wiki becomes an absolute prohibition** —
  session transcripts carry cookies and emails, and this repo is public
- The global reminder hook becomes a misfiring nudge in this repo and needs a guard —
  hooks merge across settings layers and cannot be disabled per-project;
  **voluntary retreat by the global script is the only way**

## Outcome
_Update after implementation._
