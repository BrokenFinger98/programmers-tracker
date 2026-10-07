# 2026-10-07 (evening) — the readers that followed links

Raw session record. Immutable (wiki schema §1). 16:56–23:59 KST, session `b240e44e`, sliced by KST
from the session transcript; the context was compacted at 17:25 and the inbox snapshot ends there.
Continues `2026-10-07-repairs-not-verdicts.md`; the night goes on in
`2026-10-08-the-prompt-only-the-owner-can-run.md`.

---

## #354: the audit before the fix

#354 came out of #353's review: in an isolated copy, a `statement.md` linked to
`../../.ps/git-credentials` came back from `get_problem` as the statement, push token included. The
issue named the readers that return file content over MCP. Its second comment, posted before any
code, added the previous attempt behind `diffFromPrev`. The implementer was asked to list every
reader under `problems/` and where its content goes, before writing anything.

| Reader | Where the content goes | Named by the issue |
|---|---|---|
| statement (`get_problem`) | MCP | yes |
| the same statement, inlined into the problem's `README.md` | committed and pushed | **no** |
| previous attempt (`diffFromPrev`) | the log: served, and pushed | second comment |
| `runs.jsonl` | MCP | yes |
| `examples.json` | a generated runner, pushed | **no** |
| submit code | MCP | already bounded by #353 |

All six now read through one helper, `ProblemFiles` (b70f242, ab5b8f0; 1,881 tests). Found in a
scratch copy and filed outside the scope: **writers follow links too** (a linked problem `README.md`
overwrote `.ps/git-credentials`; a `runs.jsonl` append appended to it) → #361; and **a linked
`.gitignore` is not read by git**, so `git add --all` staged `.ps/git-credentials` (git 2.48.1),
which reconciliation would commit and the next pass push → #360.

## Two review rounds and a four-hour gap

Round one (17:28–17:42). Spec review: compliant, with four doc-accuracy notes. Quality and security
review: no Critical. One Important: **every refusal was silent**, and three log lines named the wrong
cause ("has no stored code", "no examples", "recorded before it was kept"). The reviewer first pasted
the five new reader tests onto the base commit; all five failed, printing the token. It then probed
the built helper (macOS APFS, JDK 25):

| Probe | Result |
|---|---|
| `Problems` on disk, `problems` asked for | refused (the real path keeps the disk's case) |
| a hard link to the token under `problems/` | **token returned**; git cannot carry a hard link |
| a link out of `problems/` and back in | read, as designed |
| a directory swapped for a link after the check | **token read**; `NOFOLLOW_LINKS` cannot stop it |
| the last name swapped for a link after the check | read without `NOFOLLOW_LINKS`, refused with it |
| a previous attempt at `chmod 000` | absent (the base code threw `AccessDeniedException`) |
| a root configured as `a/link/../b` | every read absent |

After 17:42 nothing moved for four hours; the API was unreachable (`ENOTFOUND` at 19:15 and
20:12). The research agent for #364 stalled — "no progress for 600s" — and was resumed later. At
21:52 the owner wrote that there had been a network problem and to carry on.

The fix round (22:24): `b6048fb` (one WARN per refusal, naming the path and the reason, never the
content, an exception message or a link target; a missing file stays silent); `edd068b` (the checked
real path is opened with `NOFOLLOW_LINKS`; `readString` decodes strictly, identical to
`Files.readString` on ten edge inputs on Temurin 25); `a2cbb87` (one link test per reader method).

Re-review (22:46), one Important. **The test log capture read only the formatted message.** A
throwable passed as the last argument escaped every "never the message" assertion: the re-reviewer's
mutant (`failed()` also logging `cause`) passed all of `ProblemFilesTest` while the written log
carried the link target's path. `d04ec97` captures what a layout writes. Two claims were checked
before acting on them:

- **The `/watch` token is not in the records repository** (`compose.yaml:78-87`). Two branches had
  placed it there in seven places in the tree. The implementer fixed five (`19ff3a3`: two KDocs, the
  ADR, `mcp.md` and its twin) and the controller fixed two more (the repair-steps ADR and a test
  KDoc). The #353 plan, `b70f242`'s message and the issue body still say it.
- **`verifyBranchCoverage` runs the integration tests** → #362. A linked `examples.json` was added to
  #361.

## #354 live (23:03)

PR #363: seven CI checks, Windows the first to exercise the `NOFOLLOW_LINKS` open; squash-merged as
a3838c0 at 23:02. Before merging, the controller snapshotted every MCP read through the legacy era,
each answer's `structuredContent` saved with sorted keys:

| Snapshot | Before (22:57, old image) | After (23:03, a3838c0) |
|---|---|---|
| files | 36: 35 tool answers + the lesson list derived from `submissions` | 36, `diff -rq` identical |
| answers | `submissions`; `stats` × verdict, language, problem, part, level; `list_problems(status=attempted)`; `review_queue`; `slow_passes`; `repair_steps(limit=1000)`; `get_problem(include=[code, runs])` × 25 lessons | same |
| `repair_steps` total | 65 | 65 |
| record repository | HEAD 7e144fa, clean | 7e144fa, clean |
| boot | — | healthy after ~6 s; 0 `Treating … as absent`; 0 WARN/ERROR; `AttachReport(attached=0, deferred=0, blocked=0)` |

## #364 begins: what the client does with a prompt

The research report (21:55) read the MCP specification (`modelcontextprotocol` @ `0a11bf68c7`) and
the installed Claude Code 2.1.285's own code. It read the client's code; it did not run a prompt end
to end.

| Question | Finding | How known |
|---|---|---|
| prompt wire shape, three revisions | `"prompts": {}`; `prompts/get` takes a map of strings, answers `messages`; an unknown prompt or a bad argument is `-32602` in all three; in 2026-07-28 `prompts/list` is cacheable (`ttlMs`, `cacheScope` required) and `prompts/get` mirrors its name into `Mcp-Name` | specification |
| a value that is present but malformed | unspecified ("SHOULD validate"); a prompt result has no `isError` | specification |
| how arguments are filled | `trim().split(/\s+/)`, no quoting, mapped by position in the server's listing order | client code |
| a modern `prompts/list` without `ttlMs`/`cacheScope` | rejected by the codec, no default → no prompt shown | client code |
| the 2,048 cut | instructions and tool descriptions only, not a prompt's text | client code, docs |
| who can run a prompt | the user only; the Skill tool refuses an MCP prompt | client code |
| revision in use here | 27 tracker connections in the local MCP logs, all modern `2026-07-28`, `hasPrompts: false` | local logs |

The catalog's part names were counted for the plan: 38 of 49 contain a space and two contain a comma.
In review it emerged that 22 begin with a year, though none parses as a `since`. The plan (D1–D8)
was drafted while #354's fix round ran. #364 and its branch were created at 23:05 and the plan was
committed (b30129d).

Unit A (Tasks 1–2: the scope and the text; b9210c3, 92eb6da) was byte-identical to the plan (sha256).
An IDE crash log, `replay_pid7399.log`, had appeared at the repository root and was moved out before
it could be committed. The quality review (23:37) found three Important issues, each a hypothesis in
the plan:

- **"Match it against the part labels `stats` returns."** A part bucket has no `label`; the name is
  in `key`.
- **An empty answer.** Under a narrowing argument it would read as "no mistakes". A near miss (such as
  `python` for the id `python3`) narrows `repair_steps` to nothing in silence.
- **Unquoted values.** "String, Date" and "SUM, MAX, MIN" read as two or three arguments. From Claude
  Code, `SUM,` arrives, and was rendered as `repair_steps(part=SUM,)`. Values are now JSON strings.

The same round added "the arguments are positional — language, since, part" to the `since` refusal.
The fixes (8fd9a0b, 1be2a09) were approved at 23:55.

## A rule that 117 commits broke

At 23:22 unit A's spec reviewer pointed at `.claude/skills/commit/SKILL.md`: "never add
Co-Authored-By or any Claude/AI trailer". Measured on main at 23:23: **117 of 180 commits carry one**,
the first on 2026-08-04. So did all eight squash merges of the session, #345 through #363,
whose messages the controller had written. Implementer subagents had copied the trailer from the
controller's own instructions all day. The harness reminder that asks for it defers to the user's
instructions; the rule had been ignored, not revoked. From then on, no trailer: implementer prompts
say so and squash bodies are written by hand. Rewriting the 117 is the owner's call and was not done.

At 23:41 the owner asked the session not to stop while they slept and to keep going until it was
finished.
