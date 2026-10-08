---
type: entity
project: programmers-tracker
tags: [mcp, client-compatibility, prompt, measurement]
created: 2026-10-08
updated: 2026-10-08
sources: [raw/sessions/2026-10-07-repairs-not-verdicts.md, raw/sessions/2026-10-07-the-readers-that-followed-links.md, raw/sessions/2026-10-08-the-prompt-only-the-owner-can-run.md]
---

# Claude Code as an MCP client

The client the owner actually uses, so it is the receiving end of everything the MCP server sends.
Twice in two days, its behaviour decided what the server had to look like:

- the 2,048-character cut, during #353;
- positional prompt arguments, a codec without defaults, and a command name that differs from the
  menu entry, during #364.

None of this is in the MCP specification. The server's half is in `docs/mcp.md`; this page is the
client's half, with how each row was learned.

**Version: 2.1.285** (`claude --version`, 2026-10-07). Re-check a row before relying on it after an
upgrade. The cut became configurable in 2.1.280, and prompt handling changed in 2.1.145, 2.1.147 and
2.1.274.

## What it does with what we send

| Behaviour | How it was learned | What it decided |
|---|---|---|
| Cuts the server instructions and each tool description at 2,048 characters, keeping the head | CHANGELOG 2.1.84; the variable `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` since 2.1.280 (unset here); the session's own system prompt, which ended "…[truncated]" | every text within 2,000 characters as sent, pinned by tests ([[decisions/2026-10-07-repair-steps-are-served-not-judged]]) |
| Does not cut a prompt's text | installed code read: the cut is called for instructions and tool descriptions only | the `exam_prep` prompt carries the fuller readings ([[decisions/2026-10-07-exam-prep-asks-in-the-open]], D8) |
| Asks for `prompts/list` only when the `prompts` capability is declared | code read | the capability is declared in both eras |
| On the modern revision, requires `ttlMs` and `cacheScope` on `prompts/list`, with no default; without them it shows no prompt at all | code read | D5 |
| Splits the words after a prompt command on whitespace, without quoting, and maps them onto the arguments by position, in the order the server lists them. Extra words are dropped from `arguments` (the model still sees the raw line); an omitted argument is left out of the JSON | code read (`trim().split(/\s+/)`, then `zipObject`) | the order `language, since, part` is the interface; `part` comes last (D2) |
| Ignores `title`, icons and argument descriptions; the menu shows the description and "(arguments: …)" | code read | the argument order is the only help the menu gives |
| Merges every prompt message into one user message and ignores `role` | code read | one message (D1) |
| Never asks for `completion/complete` with `ref/prompt` | code read | no completions (D7) |
| Lets only the user run a prompt; the Skill tool refuses an MCP prompt | code read | a prompt's live acceptance needs the owner |
| The menu shows `/programmers-tracker:exam_prep (MCP)`; choosing it inserts `/mcp__programmers-tracker__exam_prep `. The display name typed without `(MCP)` matches no command; typed in full, it runs | Claude Code's docs, then the client's own parser and lookup, extracted from the binary and run (2026-10-08) | the docs name the `mcp__` command |
| Caches each server's discovery for 900 s fresh and 4 h stale | code read; checked against the client's defaults in review | live checks start with `/mcp` → Reconnect, not a new session |
| A running session does not pick up a rebuilt server's instructions on its own | an agent spawned at 02:36 on 2026-10-08 still received the pre-#353 text, three rebuilds and almost ten hours later | as above |
| Treats a non-2xx answer as a JSON-RPC error only when it is a 400 that echoes the request id; anything else is a transport fault | code read | refusals travel on 400 (modern) or 200 (handshake), as in [[decisions/2026-08-06-mcp-read-slice]]; a fault of ours travels on 200 in both eras, with its id ([[decisions/2026-10-08-a-fault-of-ours-answers-its-call-on-200]]) |
| Warns at 10k tokens of tool output, caps it at 25k by default, and writes a successful result over 50,000 characters to a file | #353 quality review | `get_problem(include=runs)` unbounded as an accepted cost |
| Negotiated the modern revision on all 27 recorded connections to the tracker | local MCP logs, 2026-10-07 | the modern path is the one in use; the handshake path stays for other clients |

## How these were learned, and what each method cannot settle

- **The CHANGELOG and docs** say what the client intends. The cut was found there. The docs also
  named the `mcp__` command, which the first draft of our own docs overlooked.
- **The client's own output** is the strongest evidence and the easiest to overlook. The system
  prompt had shown "…[truncated]" since #287 while a test asserted the text fit under 3,000.
- **Reading the installed code** gives behaviour no document states: the positional split and the
  codec. A reading is still not a run. Read from the menu code, the display name looked like the
  command. The first correction then said the display name never runs with arguments, which was also
  wrong.
- **Running the client's own functions,** extracted from the binary, settled the command name.
  Typed in full, with `(MCP)`, the display name runs.
- **A person running the prompt** is the one check the model cannot do. The acceptance of
  `exam_prep` waits on it ([[sources/2026-10-08-the-prompt-only-the-owner-can-run]]).

The general lesson is in [[concepts/assumption-vs-measurement]]: a limit tested against our own
number says nothing about what the receiving end keeps.
