# 2026-10-08 — the prompt only the owner can run

Raw session record. Immutable (wiki schema §1). 00:00–02:36 KST, session `b240e44e`, sliced by KST
from the session transcript. Continues `2026-10-07-the-readers-that-followed-links.md`, where #364's
research, plan and first unit are. The owner was asleep; the session ran on their 23:41 request to
keep going until #364 was finished.

---

## Units B–D, each implemented, then spec-reviewed, then quality-reviewed

| Unit | Commits | What review changed |
|---|---|---|
| B — Task 3, `McpPromptCatalog` | ed3333b, 84e22fd, 285e371, ce4b06d, df2ccbc | see below |
| plan fix | c9b0694 | Task 4 Step 1b would not compile as written (its fixture came a step later) and never sent an absent or `null` `arguments` |
| C — Task 4, routing in both eras | 94aa017, 7e9e924, 7fc479b, 2b6716e | `NAMED` private; controller tests pin what the wire carries |
| D — Task 5, docs | 9295c0d, ba6113a, 1671f89 | the docs followed the code where the plan was wrong |

**Unit B.** The implementer ran 27 single-point mutants against its own code: 21 died and 6
survived. Two behaviours were unpinned: a `part` reaching the text, and an array or object argument,
where a forced cast survived and would have turned a bad request into a 500. Both were pinned in
`285e371`. A third mutant, keeping only a value's first word, died only on "String, Date"; "GROUP"
alone would have missed it. The quality review (00:29) found no Critical or Important issues, and
seven Minor ones:

- Unknown argument keys were echoed unquoted. A key `"a, b"` read as two keys, and a key with a
  newline split the message (`df2ccbc`).
- A non-object `arguments` was read as no arguments, widening the session to everything on record.
  The strict reader moved to Task 4 (`7e9e924`).
- Unicode line separators and invisible format characters pass JSON quoting unescaped. Accepted:
  only the user types these.
- KDoc `[[decisions/…]]` links were unchecked; checking them was added to Task 5.

Injection probes against the rendered text: a `\n` that tried to forge a "Readings" section produced
no forged line, quotes could not forge a second argument, and C0 controls came out escaped.

**Unit C.** 1,925 tests passed, and the `NAMED` mutation failed exactly one test. The quality review
(01:20) found the same widening and the same unquoted keys on the tool path, filed as #365. A KDoc in
`RecordQuery` links a decision that does not exist, and the guards do not check `[[…]]` targets, filed
as #366; the re-review found two more such links. One implementer's mutation helper collided with
another agent's leftover `McpDispatcher.kt.orig` in the shared scratchpad, and a restore copied that
backup over the dispatcher. The backup was byte-identical to HEAD; re-hashing all 601 tracked files
found no mismatch.

**Unit D.** The plan's D3 said every refusal names the argument order. Three do: a bad `since`, a
date in `language`, and an unknown argument. The docs followed the code, and the plan was corrected.

## The final review, and a correction that overreached

The whole-branch review (02:03) cleared the branch for a PR with two Important findings.

1. **The documented command does not run.** The client's code shows the menu entry as
   `/programmers-tracker:exam_prep (MCP)`; choosing it inserts `/mcp__programmers-tracker__exam_prep `.
   The docs' `/programmers-tracker:exam_prep java` matched nothing. The docs were changed to the
   `mcp__` name. The ADR, progress and plan said the menu name does not run with arguments.
2. **"No commit carries an AI trailer" was false.** Three early branch commits, from before the
   23:22 finding, had one. The squash body would be written by hand.

The fix round (02:21) produced eight commits. `fd8a113` makes one ordered `ExamPrepScope.ARGUMENTS`
feed the listing, the unknown-argument message and the positional refusal. Against a deliberately
reordered list, the two new tests failed while 25 literal tests still passed — the gap the review had
named. The remaining commits, `babc403` through `1f4b5e7`, cover the docs: the command name; the
readings, which are in the instructions in brief and not absent; and the ADR, where the form-client
claim is now labelled an assumption and the step count is exact. The live-check steps now say
`/mcp` → Reconnect rather than "a new session", because the client caches each server's discovery
for 900 s fresh and 4 h stale.

The re-review (02:25) went one step past reading. It extracted the parser and the command lookup
from the installed 2.1.285, confirmed them byte-identical, and ran them:

| Typed | Result |
|---|---|
| `/programmers-tracker:exam_prep java 2026-09-01` | no match |
| `/programmers-tracker:exam_prep (MCP) java 2026-09-01` | runs, arguments "java 2026-09-01" |
| `/mcp__programmers-tracker__exam_prep java 2026-09-01` | runs, arguments "java 2026-09-01" |

The correction had overreached. Only the display name without its `(MCP)` token fails. The ADR,
progress and plan were fixed again. The reviewer also kept a claim in `babc403`'s body out of the
squash: "a server not proxied through claude.ai gets no aliases". Aliases attach only to Anthropic's
own design server. PR #367 passed seven CI checks and was squash-merged as fb8b79b at 02:32, with no
trailer.

## Live, server side (container started 02:33:25, healthy, 0 WARN/ERROR)

Modern revision `2026-07-28`, with the headers mirrored (`MCP-Protocol-Version`, `Mcp-Method`, and
`Mcp-Name` on `prompts/get`):

| Call | Answer |
|---|---|
| `server/discover` | 200; capabilities `[prompts, tools]`; `resultType: complete` |
| `prompts/list` | 200; `resultType: complete`, `ttlMs: 3600000`, `cacheScope: private`; `exam_prep` with `language`, `since`, `part`, none required |
| `prompts/get {language: "mysql"}` | 200; one `user` message, 2,027 characters, containing `repair_steps(language="mysql")` and the empty-answer line |

| Refusal | Status | Code | Message |
|---|---|---|---|
| `Mcp-Name: stats` on `prompts/get exam_prep` | 400 | -32020 | `Mcp-Name` is missing or disagrees with the request body |
| an unknown prompt | 400 | -32602 | "unknown prompt; this server exposes exam_prep" |
| `since: "SELECT"` | 400 | -32602 | `Since.FORMAT` + "; the arguments are positional — language, since, part" |
| `language: "2026-09-01"` | 400 | -32602 | "language \"2026-09-01\" reads as a date; …positional…" |
| `arguments: "java"` | 400 | -32602 | "arguments must be an object of strings" |

Legacy: `initialize` with `2025-11-25` reported capabilities `[prompts, tools]`. `prompts/get` with
`{}` answered with no `resultType` and one message whose scope line reads "Scope: everything on
record." The 36 snapshot files (35 tool answers and the lesson list) were identical to the
post-#354 snapshot, and the record repository stayed at 7e144fa, clean.

## What remains

The acceptance in spec §6 is a run the model cannot make. The owner must reconnect the server with
`/mcp` → Reconnect, run `/mcp__programmers-tracker__exam_prep` (or choose it from the menu) in
Claude Code, and judge whether the answer names patterns that cite record ids. Open follow-ups:
#355, #356, #360, #361, #362, #365, #366.

## Seen while ingesting

The ingest agent, spawned from this session at 02:36, received programmers-tracker's server
instructions in their pre-#353 wording: six tools, `stats` "per verdict, language or problem", cut at
"there is no cohort here… [truncated]". The server had been rebuilt three times since #353 changed
that text (16:47, 23:03, 02:33), almost ten hours earlier. A running session does not pick up a
rebuilt server's instructions on its own, which is the case the Reconnect step is for.
