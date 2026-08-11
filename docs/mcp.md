# MCP — reading your records from an AI client

> The server exposes your solving history over the Model Context Protocol so Claude, Cursor
> or a local model can read it. **Six tools, none of which write.** Four hand back stored
> records and counts; two compute a schedule or a ranking, under a boundary this page states
> before it shows them. What is not built is listed at the bottom, and the
> [README's table](../README.md) stays the authority on build status.

---

## The endpoint

```
POST http://127.0.0.1:8080/mcp
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
      "url": "http://127.0.0.1:8080/mcp",
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
| `get_problem` | `lessonId` | One lesson and every submission against it, in full — testcases and compiler output included. |
| `stats` | `groupBy` — `verdict` · `language` · `problem` | Counts per bucket. Counts only. |
| `list_problems` | `level?` · `part?` · `tag?` · `status?` | The shipped catalog joined against the records: each problem's `status` (`untouched` · `attempted` · `passed`) and its submit count. |
| `review_queue` | `limit?` | Problems due for re-solving, most overdue first, each with the attempts, help signal and pass date that set its date. |
| `slow_passes` | `thresholdMs?` | Every passed problem ranked by its slowest testcase in milliseconds, with the level, tags and language a comparison needs. |

`since` takes a date (`2026-08-01`), read in the offset the record itself carries, or a full
offset date-time (`2026-08-01T09:00:00+09:00`), read as an instant.

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

### What `slow_passes` will not decide for you

Design §6.5 asks for "markedly slower than same-tag, same-level problems". That needs peers,
and a record set this young has none — so **no baseline is applied.** The whole distribution
comes back in one call, slowest first, and where the line falls is yours.

`untimed` is part of the answer, not a footnote. SQL sends no per-case timing at all, and a
runtime error or timeout drops it case by case. Those passes are excluded from the ranking
rather than ranked as instant, because a missing reading sorted as zero would put the problems
you know least about at the fast end of a list about speed.

### Missing data looks missing

**A field that was never recorded is absent from the answer** — not blank, not zero, not
"Unknown". A problem whose title we never captured has no `title` key. A `stats` bucket with
no `key` is the count of submissions whose grouping value was never recorded, which is not
the same as a bucket named unknown.

This matters more than it sounds. A placeholder that reads like a measurement is how you get
an AI confidently describing solving habits you do not have.

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
