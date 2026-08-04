---
name: wiki-query
description: Search this repo's Wiki (docs/llm-wiki) for past decisions, progress, and know-how, and answer with sources. Use when the user asks about past context — "what did we decide before / why did we do it this way / our decision on ~".
---

# wiki-query

Wiki path: **this repo's `docs/llm-wiki/`**. The question comes as an argument.

## Process
1. **Browse the catalog** — read `docs/llm-wiki/index.md` to find relevant pages.
2. **Load pages** — read the relevant pages plus the pages they connect via `[[links]]`.
3. **For protocol questions** — also read `docs/programmers-protocol.md`. It is the single source of facts.
4. **Answer** — synthesize and answer **with source citations**. Back each key claim with
   evidence in the form `(wiki/concepts/foo.md)`.
5. **Feed back** — if the answer produced valuable new synthesis or findings, create/extend
   pages per the schema + record in `log.md`.
6. If there is **nothing** relevant, say honestly "no record in the wiki" and suggest
   ingesting via `wiki-ingest`.

Never assert unfounded speculation. Distinguish what is in the wiki from what is not.
