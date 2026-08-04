# programmers-tracker Wiki — Schema (the wiki's constitution)

This directory (`docs/llm-wiki/`) is this project's knowledge base, following the Karpathy **LLM Wiki** pattern.
It is **committed to the repo**, not kept on a personal PC, so anyone who clones gets the same knowledge and the same `/wiki-*` workflow.

> ⚠️ Use **repo-root-relative paths** only (`docs/llm-wiki/...`).
> No personal absolute paths (`/Users/...`) — they break on other people's machines.

---

## 0. This wiki's dual purpose

It differs from an ordinary team wiki in **one respect.**

1. **Development memory** — even when a session ends, we never lose "why we did it this way"
2. **Portfolio artifact** — this project doubles as a job-search portfolio.
   The wiki is the material that shows the **judgment process** that code alone cannot

Decision pages are therefore written so that **someone who wasn't in the room can still understand them.**
Not "we chose A" but "we considered B and C, chose A on ~ grounds, and accepted ~ cost in return."

What we **decided not to do, and why**, is especially valuable. In technical interviews,
"why didn't you use that" comes up more often than "why did you use that."

---

## 1. Three-layer structure

| Layer | Path | Owner | Rule |
|---|---|---|---|
| Raw Sources | `docs/llm-wiki/raw/` | Curated by humans | **Immutable**. No edits or deletions. Source of truth |
| Wiki | `docs/llm-wiki/wiki/` | **Written, updated, cross-linked by the LLM** | Humans do not write page bodies directly |
| Schema | `docs/llm-wiki/CLAUDE.md` | Configured by humans | Rule changes go in this file, not in page bodies |

```
docs/llm-wiki/
├── CLAUDE.md          # this file (schema)
├── index.md           # full catalog — read FIRST when searching
├── log.md             # ingest/query/lint history (append-only)
├── README.md          # how to use
├── raw/sessions/      # ingest sources (YYYY-MM-DD-title.md)
└── wiki/
    ├── sources/       # one summary stub per source (traceability anchor)
    ├── decisions/     # significant decisions (ADR: context/decision/rationale/options/outcome)
    ├── entities/      # external systems · libraries · APIs — the "nouns"
    ├── concepts/      # concepts · principles · know-how · debugging patterns
    └── syntheses/     # pages synthesizing 3+ sources
```

What each category holds in this project:

| Category | Contents in this project |
|---|---|
| `entities/` | Programmers ActionCable · solved.ac API · BaekjoonHub · MCP |
| `concepts/` | passive broadcast observation · verdict classification · Functional Core · protocol isolation |
| `decisions/` | adopting passive observation · choosing Kotlin · rejecting vector DB · outsourcing the tag vocabulary |
| `syntheses/` | the full protocol reverse-engineering story · weakness-analysis methodology |

---

## 2. Page-writing rules

- Filenames are **kebab-case** + `.md`. One page = one topic
- Every page starts with frontmatter:

```yaml
---
type: source | entity | concept | decision | synthesis
project: programmers-tracker
tags: [topic-tag, ...]
created: YYYY-MM-DD
updated: YYYY-MM-DD
sources: [raw/sessions/2026-08-04-foo.md]
---
```

- `type` = form classification, `tags` = topic classification (orthogonal search axes)
- `decisions/` is **one decision = one file** `<YYYY-MM-DD>-<slug>.md`, ADR format.
  **`author`** is required in frontmatter. When a decision is reversed, do not delete
  the old one — add a new file and mark the old one with `⚠️ superseded by ...`
- Every key claim cites its source: `(raw/sessions/2026-08-04-foo.md)`

### Decision page format (ADR)

```markdown
## Context
What had to be decided. What the constraints were.

## Options considered
Pros and cons of A · B · C. Record **only what was actually considered.**

## Decision
What was chosen.

## Rationale
Why. **If there is measured evidence, it must be cited.**

## Accepted costs
What this choice gave up. If "none", don't write "none" — think again.

## Outcome
What actually happened. (updated later)
```

**Measured evidence is weighted especially heavily.** This project deals with a private
protocol, so the difference between "it should be so" and "we verified it" is decisive.
If something is a guess, say it is a guess.

---

## 3. Cross-linking

- When mentioning another page, use `[[concepts/foo]]` · `[[entities/bar]]`
- Every new page **must** be registered in `index.md` and have at least 1 inbound link (no orphans)
- No over-linking — link the same target at most once per page

---

## 4. Operating principles

1. **Humans curate, the LLM writes.** Page bodies are updated only via `/wiki-ingest` · `/wiki-lint`
2. **Be selective.** The bar is "will this be worth re-reading later?" Exclude chatter and one-offs
3. **Version it with the repo.** Commit on every ingest/lint — the wiki is a first-class artifact
4. **Trust index.md.** It is the search entry point, so keep it accurate at all times
5. **Append entries start with `YYYY-MM-DD`.** Conflicts resolve deterministically via
   chronological union. `log.md` has `merge=union` in `.gitattributes`

> Until contributors appear, there is no `wiki-merge` skill (YAGNI).
> Fork-based usage produces no concurrent-write conflicts.

---

## 5. Project-specific rules

### 5.1 Protocol facts live in the protocol doc, not the wiki

`docs/programmers-protocol.md` is the **single source of truth** for protocol facts.
The wiki **references** it but never duplicates it. Duplication always drifts.

What goes in the wiki is not the *facts* but **how we discovered them and what we decided.**

```
❌ copying the message-format table into wiki/concepts/actioncable.md
✅ recording in wiki/syntheses/protocol-reverse-engineering.md:
   "we found perform(\"submit\") in the bundle and confirmed it is WebSocket.
    Details in docs/programmers-protocol.md §4"
```

### 5.2 Failed attempts are kept too

Abandoned approaches, wrong hypotheses, and misdiagnoses are **never deleted.**
From a portfolio standpoint these are exactly what demonstrates skill.

Real examples:
- confusing `challengeable_id` with the codes key and failing 4 times in a row
- misdiagnosing "weak at DFS/BFS" from `partTitle` alone

### 5.3 Ingest by session

In this project, a single conversation settles many things. When a piece of work wraps up,
leave that session in `raw/sessions/` and extract the decisions separately.
