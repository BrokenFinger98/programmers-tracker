# MCP — reading your records from an AI client

> The server exposes your solving history over the Model Context Protocol so Claude, Cursor
> or a local model can read it. **Three tools, all read-only.** What is not built is listed
> at the bottom of this page, and the [README's table](../README.md) stays the authority on
> build status.

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

## The three tools

Each returns stored records and counts. **None of them interprets, ranks or advises** — that
is the AI's job, not the server's, and it is a rule rather than an omission
([`CLAUDE.md`](../CLAUDE.md), design §7).

| Tool | Arguments | Answers |
|---|---|---|
| `submissions` | `since?` · `verdict?` | Every recorded run and submit, newest first. Per-testcase detail, compiler output and diffs are omitted here. |
| `get_problem` | `lessonId` | One lesson and every submission against it, in full — testcases and compiler output included. |
| `stats` | `groupBy` — `verdict` · `language` · `problem` | Counts per bucket. Counts only. |

`since` takes a date (`2026-08-01`), read in the offset the record itself carries, or a full
offset date-time (`2026-08-01T09:00:00+09:00`), read as an instant.

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

Design §7 sketches about twenty tools. The other seventeen need a problem catalog, the
solved.ac tag vocabulary, exam state or a review schedule, and **none of those exist yet**,
so those tools are absent rather than stubbed. A tool that answered "not implemented" would
be worse than a missing one: a client discovers it through `tools/list` and plans around it.

Not built, and not stubbed:

- `list_problems`, `attempt_diff`, `tag_problem`, `untagged`
- `warmup_plan` · `warmup_reset` · `warmup_report`
- `review_queue` · `slow_passes` · `performance` · `stuck_testcases` · `company_profile`
- `exam_start` · `exam_status` · `exam_finish`
- `append_retro` · `mark_hint` · `push`
- MCP **resources** (`ps://…`) and **prompts** — the server declares only the `tools`
  capability

There is also no pagination: `submissions` with no arguments returns the whole log. That is
fine for one person's history today and would need a bound before it is not.
