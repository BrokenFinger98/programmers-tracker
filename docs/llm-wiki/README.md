# programmers-tracker Wiki

This project's knowledge base. Development memory and a **portfolio artifact**.

## How to use

| Command | When |
|---|---|
| `/wiki-ingest` | When a piece of work wraps up with significant decisions or artifacts |
| `/wiki-query <question>` | "What did we decide before?" · "Why did we do it this way?" |
| `/wiki-lint` | Periodic health check — contradictions · orphan pages · index mismatches |

Skill definitions live in `.claude/skills/wiki-*/`; the schema is in `CLAUDE.md`.

## Principles

- **Humans curate, the LLM writes.** Page bodies are not written by hand
- **Protocol facts do not go here.** `docs/programmers-protocol.md` is the single source of truth
- **Failed attempts are kept too.** Abandoned approaches and wrong hypotheses are what carry the value
