---
name: wiki-ingest
description: Ingest and organize conversations, decisions, and deliverables into this repo's Wiki (docs/llm-wiki). Use when the user says "ingest/organize/record this", or when a piece of work wraps up having produced important decisions, deliverables, or reusable know-how. Exclude chit-chat and one-offs.
---

# wiki-ingest

Wiki path: **this repo's `docs/llm-wiki/`** (relative to the repo root — no personal absolute paths).

You are this wiki's **editor**. Always follow the `docs/llm-wiki/CLAUDE.md` schema.

## Process
1. **Load the schema** — read `docs/llm-wiki/CLAUDE.md` and `docs/llm-wiki/index.md`.
2. **Pin down the sources** — arguments take priority if given. Otherwise, pick from the
   current conversation only what is worth revisiting
   (key decisions · work deliverables · reusable know-how · measured results · **hypotheses that turned out wrong**).
3. **Save raw** — `docs/llm-wiki/raw/sessions/YYYY-MM-DD-<title>.md` (immutable, never edit).
   If the path exists, use a `-2` suffix.
4. **Integrate into the wiki (never overwrite — merge)** — update existing pages by weaving
   into the body (`updated:` to today, add the new raw to `sources:`. On conflict, preserve
   the old content as `⚠️ (old) ...`).
   Create new pages under `wiki/` with proper schema frontmatter.
   - ★ **One decision = one file**: `wiki/decisions/<YYYY-MM-DD>-<slug>.md`, `author`·`created`·`updated` required.
   - ★ **ADR format**: Context / Options considered / Decision / Rationale / **Accepted costs** / Outcome.
5. **Never duplicate protocol facts** — `docs/programmers-protocol.md` is the single source.
   The wiki records only *how we found out and what we decided*, referencing document sections.
6. **Cross-links + index** — connect with `[[...]]` + register new pages in `index.md` (no orphans).
   Appended entries **start with the date** so merges sort chronologically.
7. **Check for contradictions** — resolve conflicts with `⚠️`, latest content canonical.
8. **Log** — append to `docs/llm-wiki/log.md`:
   `## [YYYY-MM-DD] ingest | <title> → N pages updated, M created`.
9. **Commit** — from the repo root: `git add docs/llm-wiki && git commit`.

## Project-specific
- **Always cite measured evidence.** Distinguish "it probably is" from "we verified it".
- **Record failed attempts and wrong diagnoses too.** They have portfolio value.
- Write decision pages **so that someone who wasn't in the room can understand them**.
