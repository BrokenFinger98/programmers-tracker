---
type: source
project: programmers-tracker
tags: [mcp, prompt, client-compatibility, measurement, failed-attempts]
created: 2026-10-08
updated: 2026-10-08
sources: [raw/sessions/2026-10-08-the-prompt-only-the-owner-can-run.md]
---

# 2026-10-08 session summary — the prompt only the owner can run

## Key claims

1. **`exam_prep` shipped in PR #367 (fb8b79b).** It was built unit by unit while the owner slept.
   Mutation runs found the gaps the planned tests left. Review found:
   - unquoted unknown keys;
   - a non-object `arguments` that widened the session to everything on record (#365 for the tools);
   - "every refusal names the order", which is true of three refusals.
2. **The documented command did not run, and the first correction overreached.** The menu shows
   `/programmers-tracker:exam_prep (MCP)`. That display name, typed without its `(MCP)` token,
   matches no command; typed in full, it runs, as does the inserted
   `/mcp__programmers-tracker__exam_prep`. This was settled by running the client's own parser,
   not by reading it.
3. **The server side was verified live at 02:33.**
   - `server/discover` lists prompts.
   - `prompts/list` is cacheable (`ttlMs` 3600000, `cacheScope` private).
   - `prompts/get` returns one 2,027-character message.
   - Five refusals return 400, with -32020 or -32602.
   - The legacy handshake works.
   - The 36 tool-read snapshot files are unchanged.
4. **The acceptance is pending, because only the owner can run a prompt.** The owner runs it after
   `/mcp` → Reconnect. The discovery cache (900 s fresh, 4 h stale) is why. While ingesting, a new
   agent of the same session still received the instructions as they were before #353, three
   rebuilds later.

## Pages this source updated

[[decisions/2026-10-07-exam-prep-asks-in-the-open]] · [[entities/claude-code-mcp-client]] ·
[[concepts/assumption-vs-measurement]] · [[concepts/orchestrated-implementation]]
