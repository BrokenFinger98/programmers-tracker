---
type: decision
project: programmers-tracker
tags: [mcp, prompt, interpretation-boundary, client-compatibility]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-08
sources: [raw/sessions/2026-10-07-the-readers-that-followed-links.md, raw/sessions/2026-10-08-the-prompt-only-the-owner-can-run.md]
---

# The exam_prep prompt asks in the open

## Context

The server counts and names nothing: what a count means about the learner is the reader's call
([[decisions/2026-08-12-the-server-counts-and-names-nothing]]). The diagnosis of recurring mistakes
went to the learner's own model, over repair steps the server serves, and nothing it concludes is
stored ([[decisions/2026-10-07-mistake-patterns-are-diagnosed-not-stored]]). Part 4.4 of
`docs/superpowers/specs/2026-10-07-mistake-patterns-design.md` named the place where that diagnosis
is *asked for*: one MCP prompt, `exam_prep(language?, since?, part?)`, run before a coding test. What
had to be settled was how a server that names nothing hands over a request to name, and what the
client people actually use does with a prompt's arguments.

The instructions were already full. #353 fit them within the 2,000 characters a client receives
whole, at 1,973 — 27 left ([[decisions/2026-10-07-repair-steps-are-served-not-judged]]). They state
the readings a pre-exam session must not get wrong, but only in brief, and have no room for more.

Read and measured before planning (plan `docs/superpowers/plans/2026-10-07-the-exam-prep-prompt.md`):

- **The specification** (`modelcontextprotocol/modelcontextprotocol` @ `0a11bf68c7`, schemas for
  `2025-06-18`, `2025-11-25` and `2026-07-28`). The capability is `"prompts": {}`. `prompts/get`
  takes `arguments` as a map of strings and answers `messages`. An unknown prompt and an invalid
  argument are `-32602` in all three revisions. In `2026-07-28` a `prompts/list` result is cacheable,
  with `ttlMs` and `cacheScope` required, and `prompts/get` mirrors its name into `Mcp-Name` as
  `tools/call` does.
- **Claude Code 2.1.285** — its docs, its CHANGELOG and the installed client's code, read rather than
  run end to end (each fact, with how it was learned, is in [[entities/claude-code-mcp-client]]). It asks for `prompts/list` only once the capability is declared, and lists the
  prompt as `/programmers-tracker:exam_prep (MCP)`. It **splits the words after the command on
  whitespace, without quoting, and maps them onto the arguments in the order the server lists
  them**; extra words are dropped from `arguments`, though the model still sees the raw line. It
  ignores `title` and the argument descriptions, merges every message into one user message
  regardless of `role`, and never asks for `ref/prompt` completions. It cuts instructions and tool
  descriptions at 2,048 characters, but not a prompt's text. Only the user can run a prompt; the
  model cannot. **Its modern codec reads `ttlMs` and `cacheScope` on `prompts/list` with no
  default, and without them shows no prompt at all.**
- **38 of the catalog's 49 part names contain a space** ("GROUP BY", "코딩 기초 트레이닝",
  "String, Date"), and two contain a comma ("String, Date", "SUM, MAX, MIN") —
  `src/main/resources/catalog.json`, counted again on 2026-10-08. In Claude Code a `part` arrives as
  its first word.
- **This machine's Claude Code connected to the tracker 27 times, all on the modern revision
  `2026-07-28`** (its local MCP logs, 2026-10-07). The modern path is the one in use; the handshake
  path stays for other clients.

## Options considered

The plan's decision table (D1–D8) and the review rounds on #364 are the record; only what was
weighed there is listed.

1. **Where the fuller readings live.** The instructions state them in brief. (a) Expand them there,
   beside the others — 27 characters were left, and Claude Code keeps the head of an overrun, so the
   expansion would be lost without a word. (b) In the prompt's own text — chosen (D8).
2. **Checking `part` against the catalog**, refusing a part no problem carries — rejected: Claude
   Code sends "GROUP" for "GROUP BY", so the check would refuse what the main client sends for 38 of
   49 parts. The model reconciles the word instead (D3).
3. **The argument order.** (a) Any order with `part` before another argument — with `language,
   part, since`, the "BY" of "GROUP BY" lands in `since` and is refused as a date. (b) `language,
   since, part` — chosen (D2).
4. **More than one message**, for instance the readings apart from the request — unused: Claude
   Code merges every message into one user message and ignores `role` (D1).
5. **Argument completion** (`completion/complete` for `ref/prompt`), offering the catalog's part
   names as the user types — rejected: Claude Code never asks for it (D7).
6. **A malformed `arguments`.** (a) Read it as none, as the tools' reader does — `"arguments":
   "java"` would prepare a session over everything on record and look right. (b) Refuse it —
   chosen in review.
7. **How values reach the text.** (a) As typed, as first planned — "String, Date" reads as two
   arguments. (b) As JSON strings — chosen in review.

## Decision

- **One prompt, `exam_prep`, with every argument optional (D1).** `prompts/get` answers one `user`
  text message: an opening, the scope, the request ("The tools count and name nothing. Here the
  naming is asked for"), five numbered steps, and the readings. Steps 1–4 are spec §4.4's; step 5,
  what the records could not support, is a closing step the spec does not list. The readings are
  the spec's own step 5, set apart after the steps. The steps: call `stats(groupBy=part)` and
  `stats(groupBy=level)`; call `repair_steps(...)` over the scope, read past `truncated`, and
  cluster the steps into patterns named by their diffs, citing record ids and counting the problems
  each spans — a pattern seen once is not a pattern; for each pattern, the problems to re-solve and
  up to three untouched ones from
  `list_problems(status=untouched, part=<a part its steps come from>)`; two or three drills for each
  pattern; and what the records could not support.
- **The argument order is an interface: `language`, `since`, `part` (D2).**
- **`since` is checked; `language` and `part` are taken as typed (D3).** `since` goes through the
  tools' own `Since` parser before anything runs. A `language` that reads as a date is refused as a
  positional slip, and is checked first. Values are rendered as JSON strings, in the scope paragraph
  and in the `repair_steps` call. Whenever an argument narrows, the paragraph says that only
  `repair_steps` takes the scope, and that an empty answer under it is not an absence of mistakes:
  the model is to say so and name the argument that may not match. A given part comes with a
  warning: it may be the start of a longer name, so match it against the part **keys**
  `stats(groupBy=part)` returns, call `repair_steps` with the full name, and say which part was
  used. The refusals of a bad `since`, of a date in `language` and of an unknown argument name the
  order.
- **Refusals are `-32602` (D4)**, carried on HTTP 400 to a modern client and 200 to a handshake one,
  as an unknown tool's is: an unknown prompt; an unknown argument, each key JSON-quoted, with the
  arguments the prompt takes in order; a value that is not a JSON string (`<name> must be text`);
  a `since` that does not parse (`Since.FORMAT` and "the arguments are positional — language, since,
  part"); a `language` that reads as a date; and an `arguments` that is not an object ("arguments
  must be an object of strings"). A prompt's `arguments` is read by its own strict reader,
  `McpCall.promptArguments()`: absent or null is none, an object is used, anything else is refused.
  A blank or null argument is not given.
- **The modern `prompts/list` is cacheable (D5)**: `ttlMs` (an hour) and `cacheScope: private`,
  beside the `resultType: complete` every modern result carries.
- **`prompts/get` is held to `Mcp-Name` (D6)**, as `tools/call` is, with the Base64 sentinel decoded
  before comparing. `McpCall.toolName()` became `name()`: it reads `params.name` for both methods.
- **No `listChanged`, no completions (D7).**
- **The prompt carries its own readings, and no length budget is tested on it (D8)**: a step shows
  what changed, not what was wrong; a run is not an attempt; absent is not zero; run code is kept
  from the 2026-10-07 tracker on; `codeLate`; `incompleteHistory`.

## Rationale

A prompt keeps the asking visible. The tools' answers stay counts and records. The request to name
is text the learner chooses to send to their own model, through a command only they can run, and it
says in its own words that it is asking. Nothing the model concludes comes back. Both earlier
decisions hold: the server still names nothing, and the diagnosis stays on demand.

The order follows from how Claude Code fills arguments — split on whitespace, without quoting, by
position (read from 2.1.285) — and from the measured part names, 38 of 49 with a space. In last
place, the words after a part's first fall off the end. Anywhere else they would land in the next
argument, and with `since` after `part` that argument is refused. `language` comes first because it
is the one most sessions give.

`since` is strict because a bad bound prepares the session over the wrong range and looks right.
`part` is not, because a check would refuse "GROUP", the very word the main client sends; the
model can compare it with the keys `stats(groupBy=part)` returns. "Keys", not "labels": a part
bucket carries the part in `key` and has no `label` — the plan said "labels" until review read the
answer. A near miss narrows `repair_steps` to nothing in silence: `python` where the id is
`python3`, or a part typed where `language` goes. That is why the empty-answer line appears
whenever anything narrows. Values are quoted because "String, Date" and "SUM, MAX, MIN" are real
part names, and unquoted a model reads each as two arguments. Unknown keys are quoted for the same
reason, and so that a newline in a key cannot break the message. A refusal names the order because
`/exam_prep mysql SELECT` and `/exam_prep 2026-09-01` are the likely slips.

`-32602` is the specification's code for an unknown prompt and an invalid argument in all three
revisions, and a prompt's answer has no `isError`, so there is no tool-error channel to use instead.
The 400/200 carriage is the one [[decisions/2026-08-06-mcp-read-slice]] gave JSON-RPC failures,
because a handshake-era client reads a non-2xx as a transport fault. A blank is "not given" on an
assumption: a form-style client may send an empty field as `""`. None was measured, and Claude Code
never sends one. `arguments` is strict because every argument is optional, so `{}` is a whole
request: a malformed `arguments` read as `{}` would widen the session to everything on record, the
failure `since` is refused to prevent.

D5 is required by the revision and by the client in use: Claude Code's modern codec has no default
for `ttlMs` and `cacheScope` and shows no prompt without them, and all 27 measured connections were
modern. D6 is the binding's requirement for `prompts/get`. D7: the prompt set is fixed at compile
time, and Claude Code never asks for completions, so they would serve no client in use. D8: the
instructions state the readings in brief and, with 27 characters left, have no room for more, so
the prompt carries the fuller wording a pre-exam session needs; Claude Code does not cut a prompt's
text. The text is kept short because it is pasted into the conversation every time it runs, not to
meet a cap.

## Accepted costs

- **A part given in Claude Code arrives cut at its first space.** The model, not the server,
  reconciles it.
- **In Claude Code a `since` needs a `language` before it, and a `part` needs both.** The order is
  the whole interface its menu offers.
- **A blank argument counts as not given here, where a tool refuses a blank**, on an assumption: a
  form-style client may send an empty field as `""`. None was measured, and Claude Code never sends
  one.
- **JSON quoting escapes quotes, backslashes and C0 controls, but not Unicode line separators**
  (U+2028, U+2029, U+0085) **or invisible format characters** (ZWSP, RLO) — checked on
  kotlinx-serialization 1.11.0, the version in use. A value can therefore still look odd in the
  text. Only the user types these arguments, and the model cannot run a prompt, so the only one
  misled is the user's own session.
- **No length bound on an argument.** The endpoint is loopback-only by default
  (`TRACKER_BIND_ADDRESS` natively, the published address in the container), behind the token and
  Origin checks.
- **`language` and `part` are unchecked**, so a typo answers empty rather than refused; the text
  tells the model to say so.
- **Claude Code reads neither `title` nor the argument descriptions.** The argument order is all
  the help its menu gives.
- **The text is English**; the client's model chooses the answer's language.
- **The same widening and unquoted keys remain on the tool path (#365).** Five of the seven tools
  read a malformed `arguments` as none, and every tool echoes unknown keys unquoted.

## Outcome

⚠️ (the server half of this check was verified on 2026-10-08; see below) **Implemented on #364;
live check pending:** the rebuilt container answers `prompts/list` and `prompts/get`; then the
owner reconnects the server with `/mcp` → Reconnect and runs `/mcp__programmers-tracker__exam_prep`
— the name choosing it from the menu inserts — in Claude Code (the model cannot). Spec §6 accepts
it when the answer names patterns that cite record ids. The final review read 2.1.285 again and ran
its parser and command lookup. The menu's
`/programmers-tracker:exam_prep (MCP)` is a display name: typed without its `(MCP)` token, as
`/programmers-tracker:exam_prep java`, it matches no command; typed in full it runs, as does the
inserted `mcp__` name. The client also caches each server's discovery for up to 900 s fresh and
4 h stale, so a new session may still show no prompt.

**Server side verified live on 2026-10-08 at 02:33 KST.** PR #367 was squash-merged as main
`fb8b79b` and the container rebuilt. It came up healthy, with no WARN or ERROR in the log
(raw/sessions/2026-10-08-the-prompt-only-the-owner-can-run.md).

The calls used the modern revision `2026-07-28`, with `MCP-Protocol-Version` and `Mcp-Method`
mirrored on every request and `Mcp-Name` on `prompts/get`:

- `server/discover` declares `[prompts, tools]` with `resultType: complete`.
- `prompts/list` answers with `resultType: complete`, `ttlMs: 3600000` and `cacheScope: private`.
  `exam_prep` takes `language`, `since` and `part`, none of them required.
- `prompts/get` with `{language: "mysql"}` answers with one `user` message of 2,027 characters. It
  contains `repair_steps(language="mysql")` and the empty-answer line.

| Refusal | Status | Code | Message |
|---|---|---|---|
| `Mcp-Name` disagrees with the body | 400 | -32020 | |
| unknown prompt | 400 | -32602 | "unknown prompt; this server exposes exam_prep" |
| `since: "SELECT"` | 400 | -32602 | `Since.FORMAT`, then "; the arguments are positional — language, since, part" |
| `language: "2026-09-01"` | 400 | -32602 | "language \"2026-09-01\" reads as a date; …positional…" |
| `arguments: "java"` | 400 | -32602 | "arguments must be an object of strings" |

On the legacy revision:

- `initialize` with `2025-11-25` declares `[prompts, tools]`.
- `prompts/get` answers with no `resultType` and one message. Its scope line reads "Scope: everything
  on record."

The tools did not change. All 36 snapshot files match the snapshot taken after #354, and the record
repository stayed at 7e144fa with a clean status.

**Still pending: the owner's run.** The owner runs `/mcp__programmers-tracker__exam_prep` in Claude
Code after `/mcp` → Reconnect. Spec §6 accepts it when the answer names patterns that cite record
ids.

The need to reconnect showed up again during the ingest. An agent spawned from this session at
02:36 received the server instructions as they stood before #353, cut at 2,048 characters. The
client entity page linked under Context has the details.

Built on `feat/364-exam-prep-prompt` from the plan, reviewed task by task for spec compliance and
quality. Review changed the planned scope and text in four places — quoted values, part keys rather
than labels, the empty-answer line, refusals that name the order — and added the strict reader for
a prompt's `arguments`. A mutation pass over the catalog added two pins: a `part` sent through it,
and an array or object refused as "must be text" rather than thrown as an internal fault. The final
review made one ordered list, `ExamPrepScope.ARGUMENTS`, feed the listing, the unknown-argument
message and the positional refusal (`fd8a113`). Against a deliberately reordered list, the two new
tests failed while 25 literal tests still passed.

Found in review and filed rather than fixed on this branch:

- **#365** — the tool path reads a malformed `arguments` as none and echoes unknown keys unquoted.
- **#366** — a KDoc in `RecordQuery` links a decision that does not exist, and `scripts/guards.sh`
  does not check `[[…]]` targets. This branch checked the links its own KDocs make by hand.
