---
name: wiki-lint
description: Health-check and tidy this repo's Wiki (docs/llm-wiki) — contradictions, stale information, orphan pages, missing links, index mismatches. Use when the user asks to "tidy/check/lint the wiki".
---

# wiki-lint

Wiki path: **this repo's `docs/llm-wiki/`**. The `docs/llm-wiki/CLAUDE.md` schema is the standard.
Scope can be narrowed via arguments (empty = everything).

## Checks
1. **Contradictions** — ① check `sources:` → ② the side with the newer `updated:` (or `created:` if absent)
   is canonical; preserve the old claim as `⚠️ (old) ...` ③ if undecidable, report as
   `⚠️ manual check needed` (no arbitrary judgment calls).
2. **Stale information** — update/retire old conclusions invalidated by newer sources.
3. **Orphan pages** — connect/merge pages with no inbound `[[link]]`. (`sources/` stubs are exempt)
4. **Missing cross-links** — add only where semantically directly related. No over-linking.
5. **Index consistency** — `index.md` ↔ actual files under `wiki/`, 1:1.
6. **Frontmatter** — fill in `type`·`project`·`updated`·`sources`.
7. **Protocol duplication check** — if the wiki duplicates facts from
   `docs/programmers-protocol.md`, replace with references. Duplicates always drift.
8. **Decision page format** — verify all 6 ADR sections (Context/Options/Decision/Rationale/Accepted costs/Outcome)
   are present. If "Accepted costs" is empty, request that it be filled in.

## Wrap-up
Report a summary of changes → append `## [YYYY-MM-DD] lint | <summary>` to `docs/llm-wiki/log.md`
→ from the repo root: `git add docs/llm-wiki && git commit`.
