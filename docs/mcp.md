# MCP — reading your records from an AI client

**[한국어](mcp.ko.md)**

> The server exposes your solving history over the Model Context Protocol so Claude, Cursor
> or a local model can read it. **Six tools, none of which write.** Four hand back stored
> records and counts; two compute a schedule or a ranking, under a boundary this page states
> before it shows them. What is not built is listed at the bottom, and the
> [README's table](../README.md) stays the authority on build status.

---

## The endpoint

```
POST http://127.0.0.1:1619/mcp
X-Tracker-Token: <your token>
```

Streamable HTTP, on the same process and port as `/watch`. There is no stdio bridge: stdio
is one server per client process, and this server holds a live grading subscription and an
exclusive lock on the record repository, so a second copy per client cannot exist.

### The token

**The same local token as `/watch`.** One process, one credential — a second one would
double what you have to copy and protect nothing extra, since both guard the same process.

If you never set `TRACKER_WATCH_TOKEN`, the server generated one on first start and wrote
it owner-only to `.ps/watch-token`. Print it with:

```bash
cat .ps/watch-token
```

Authorization is never off and there is no default value. This endpoint answers with your
entire solving history, and loopback is shared with every other process on the machine.

### Connecting a client

Most clients take a URL and a headers map. The shape is:

```json
{
  "mcpServers": {
    "programmers-tracker": {
      "url": "http://127.0.0.1:1619/mcp",
      "headers": { "X-Tracker-Token": "<paste from .ps/watch-token>" }
    }
  }
}
```

Consult your client's own documentation for where that block goes — it differs per client
and changes between releases, so it is deliberately not reproduced here.

---

## The six tools

Four return stored records and counts and nothing else. **None of them interprets, ranks or
advises** — that is the AI's job, not the server's, and it is a rule rather than an omission
([`CLAUDE.md`](../CLAUDE.md), design §7).

`review_queue` and `slow_passes` compute something, and the boundary they respect is worth
knowing before you read their answers: they schedule and rank, they do not diagnose. Every item carries
the facts that set its date so you can disagree with the schedule — see
[`decisions/2026-08-10-scheduling-is-not-diagnosis`](llm-wiki/wiki/decisions/2026-08-10-scheduling-is-not-diagnosis.md).

| Tool | Arguments | Answers |
|---|---|---|
| `submissions` | `since?` · `verdict?` | Every recorded run and submit, newest first. Per-testcase detail, compiler output and diffs are omitted here. |
| `get_problem` | `lessonId` | One lesson and every grading against it, in full — testcases and compiler output included. `submissionCount` and `runCount` split them. |
| `stats` | `groupBy` — `verdict` · `language` · `problem` | Counts per bucket. Counts only. **Submits, never runs.** **`problem` counts across languages.** |
| `list_problems` | `level?` · `part?` · `tag?` · `status?` | The shipped catalog joined against the records: each problem's `status` (`untouched` · `attempted` · `passed`) and its submit count. |
| `review_queue` | `limit?` | Problems due for re-solving, most overdue first, each with the attempts, help signal and pass date that set its date. **One entry per language.** |
| `slow_passes` | `thresholdMs?` | Every passed problem ranked by its slowest testcase in milliseconds, with the level, tags and language a comparison needs. |

`since` takes a date (`2026-08-01`), read in the offset the record itself carries, or a full
offset date-time (`2026-08-01T09:00:00+09:00`), read as an instant.

**The two surfaces group differently, on purpose.** `review_queue` and `slow_passes` key on
(problem, language) — a pass demonstrates a language, so solving something in Java does not
schedule away the Kotlin version
([`decisions/2026-08-11-a-pass-belongs-to-its-language`](llm-wiki/wiki/decisions/2026-08-11-a-pass-belongs-to-its-language.md)).
`stats(groupBy=problem)` counts submissions per problem and collapses the languages, because
"how many times did I submit this problem" is the question it answers. Read side by side
without knowing that, one problem showing seven `review_queue` items and one `stats` bucket
looks like a disagreement. `groupBy=language` is the other axis.

**`stats` counts submits and `submissions` returns runs too**, which is why the same problem can
show 8 in a tally and 15 in the list. A run is not an attempt (design §5.1), and a verdict tally
that counted them read as a compile-error problem where there was only someone pressing Run
while writing code — `stats` answered otherwise until #235, and `get_problem`'s
`submissionCount` until #237. Where a count could mean either, both are given:
`get_problem` answers `submissionCount` **and** `runCount`, the same two words
`problems/<id>/README.md` has always used.

`list_problems` is the only one that can answer **"which of these have I never touched"** —
the records alone cannot tell that from "tried and failed", since both are simply absent
from a verdict tally. Its filters all narrow, and a filter naming something the catalog does
not contain returns nothing rather than everything.

### What `review_queue` will and will not tell you

The interval comes from two things: how many submits the pass took, and whether the questions
tab was opened while you were stuck. It does **not** come from how long you spent —
`focusedSec` is reported on every item and never scored, because calibrating it needs a
per-level distribution of how long problems actually take and no such measurement exists yet.
Weigh it yourself.

A pass recorded with no browser extension watching has **no** `sawQuestions` key. That is not
`false`. Such an item can never reach the longest interval, because reading "we were not
watching" as "no help was taken" is the one error that pushes a shaky problem two months out.

The numbers behind the four bands — 60, 21, 7 and 3 days — are chosen, not measured. They
reproduce the two examples design §6.4 states and nothing more.

**A pass counts for the language it was written in, and no other.** The same `lessonId` can
appear twice with two `language` values and two schedules, and the submits that led to one are
not counted against the other. Someone practising Java because a company does not offer their
usual language would otherwise be told a Kotlin pass had covered it.

Whether solving it once "really" means you can write it in another language is a claim about the
learner, and this server does not make those — it schedules. Both entries carry the facts that
scheduled them; disagree with either and you can see exactly why.

### What `slow_passes` will not decide for you

Design §6.5 asks for "markedly slower than same-tag, same-level problems". That needs peers,
and a record set this young has none — so **no baseline is applied.** The whole distribution
comes back in one call, slowest first, and where the line falls is yours.

A reading belongs to a **(problem, language)** pair for the same reason: a runtime measures the
solution you wrote, not the problem you solved. Grouping by problem alone kept only the latest
pass, so a slow Java pass written the day after a fast Kotlin one disappeared — the exact reading
this tool exists to surface, hidden by the tool.

**Compare within one language, and the reason is measured.** Lesson 181952 was passed in all
seven supported languages on 2026-08-12, and its answer is a single statement in each of them:

| java | kotlin | javascript | csharp | python3 | c | cpp |
|---|---|---|---|---|---|---|
| 83.33 ms | 65.19 ms | 36.28 ms | 22.42 ms | 10.74 ms | 1.17 ms | 1.12 ms |

**74×, and none of it is the code.** It is interpreter and VM startup. Read across languages and
the top of this list is "which runtime starts slowest"; read within one and it is what the tool
is for. The `language` field on every item is there to be used, not merely disclosed.

`untimed` is part of the answer, not a footnote. SQL sends no per-case timing at all, and a
runtime error or timeout drops it case by case. Those passes are excluded from the ranking
rather than ranked as instant, because a missing reading sorted as zero would put the problems
you know least about at the fast end of a list about speed.

### `elapsedSec` is not how long you spent

It is **wall clock since the problem was first opened** — sleep, other work and the days between
sessions included. The timer starts the first time a problem is seen and never restarts, so it is
calendar time from first encounter to that grading.

One measured record:

```json
"elapsedSec": 77251,          // 21.5 hours
"sensor": { "focusedSec": 37 }
```

Half a minute of work on a tab left open overnight. Both numbers are right; they answer different
questions, and only `focusedSec` answers "how long did this take". They can differ by orders of
magnitude, so a conclusion about effort drawn from `elapsedSec` will be wrong in a way nothing in
the answer marks as wrong — which is why the tools that return records say this in their
descriptions too.

`focusedSec` is absent whenever no extension was watching, and that absence is not zero.

`sincePrevSec` is the third of the trio: **seconds since the previous recorded grading of the same
problem**, any action and any language. It separates five submits in ninety seconds from five
across three evenings — guessing from thinking — and it is reported, never scored. Null when there
is genuinely no previous grading.

### The problem's own words, when we have them

`get_problem` carries `statement` — the problem description as Programmers worded it, converted to
Markdown and stored in your record repository the first time that problem was graded. It is the
one thing on this surface that is **not** about you, and without it an AI reading these records
knows testcase 3 failed and nothing about what was asked.

It is **absent for problems recorded before the server began keeping it**, and a boot-time pass
fills those in a few at a time. `kind` is the same shape: `algorithm` or `database`, taken from
the channel the grading was broadcast on, and missing from records older than the field.

Neither absence says anything about the problem. Both say something about when you solved it.

### Missing data looks missing

**A field that was never recorded is absent from the answer** — not blank, not zero, not
"Unknown". A problem whose title we never captured has no `title` key. A `stats` bucket with
no `key` is the count of submissions whose grouping value was never recorded, which is not
the same as a bucket named unknown.

This matters more than it sounds. A placeholder that reads like a measurement is how you get
an AI confidently describing solving habits you do not have.

### A history with holes says so

**Every answer** carries `incompleteHistory` when frames were captured for gradings that **no
record represents** — a grading whose opening frame was missed cannot be turned into a record, and two
of them exist on the author's machine from defects fixed on 2026-08-11. The field names the
lessons and counts the frames.

It is absent when there are none, so its presence is the signal. It used to appear on `stats`
alone, on the argument that a total is where a denominator matters most — true, and not enough: a
pass whose frames were orphaned is a problem `review_queue` will never schedule and a reading
`slow_passes` cannot rank, and neither said so. Every tool reads the same history, so every answer
admits the same holes.

The answer carries counts; the explanation is in each tool's description, which you receive once
from `tools/list` rather than on every call. This is the same rule as the paragraph above, one level up: the *record*
can be missing data too, not only a field.

They are not recoverable, and that is deliberate. The missing `start` frame carries the testcase
ids and the problem's published examples, and pairing a stretch of orphaned frames with the
attempt it belongs to would be a guess — the file is per-lesson and append-only, so several
gradings sit in it end to end with nothing between them.

---

## Protocol revisions

MCP is dated and versioned, and it changed shape: revision `2026-07-28` removed the
`initialize` handshake, protocol-level sessions and `ping`, and moved the protocol version
onto every request. This server answers **both** eras, so it works with clients shipping
today and with clients built against the current specification:

| Era | Revisions | Opened with |
|---|---|---|
| Modern | `2026-07-28` | Per-request `_meta`, `server/discover` |
| Handshake | `2025-11-25` · `2025-06-18` | `initialize` |

You do not have to configure this. The server decides from how your client opens.

---

## Security posture

- **Loopback only** by default (`server.address`), and the token is required regardless.
- **Any request carrying an `Origin` header is refused** with `403`. A native MCP client
  sends none; a browser does, and this is the DNS-rebinding defence the MCP specification
  requires. Admit one deliberately with `TRACKER_MCP_ALLOWED_ORIGINS` if you need to.
- **Read-only.** The tools reach the record repository through a query that cannot append,
  move or commit, so a prompt-injected "delete my failures" has no path to act on.
- **No Programmers session cookie is ever on this path**, at any log level.
- Nothing is logged on the normal path — not the request and not the answer — because every
  answer is a piece of your solving history.

---

## What is not built

Design §7 sketches about twenty tools. Nothing missing is **stubbed**: a tool that answered
"not implemented" would be worse than a missing one, because a client discovers it through
`tools/list` and plans around it.

"Not built" was hiding four different situations, so here they are apart (#140):

**Already deliverable — no tool will be added.** The data is in the tools above and the
grouping is the client's, which is the line the design's own §6 opening draws.

| sketched tool | do this instead |
|---|---|
| `attempt_diff` | `get_problem` returns `diffFromPrev` on every record |
| `stuck_testcases` | `get_problem` returns the full `testcases` array |
| `performance` | `acceptanceRate` and `level` are on every record and every catalog entry |
| `company_profile` | `list_problems` returns `part` and `tags` on all 689 problems; group them yourself. A company axis is *not* built on purpose — `partTitle` is 49 values of which about half are learning tracks, so grouping them into companies would mean maintaining a map over labels Programmers can change at will |

**Needs a decision before any code.** All of these write, and read-only is a security
property here rather than a scope one: an AI holding the token cannot alter a solving record
however it is prompted.

- `exam_start` · `exam_status` · `exam_finish`
- `append_retro`
- `push`

**Deleted.** `warmup_plan` · `warmup_reset` · `warmup_report` — with design §6.3, because past
problems are out of scope. `mark_hint` — with `hintLevel` in #136, which removed a field that
was being served as a measurement nobody had taken. `tag_problem` · `untagged` — the catalog
ships classified.

**Genuinely absent.** MCP **resources** (`ps://…`) and **prompts**; the server declares only
the `tools` capability.

There is also no pagination: `submissions` with no arguments returns the whole log. That is
fine for one person's history today and would need a bound before it is not.
