# 2026-08-13 — the map becomes a workspace

Raw session record. Immutable (wiki schema §1). Continues
`2026-08-13-the-tally-that-counted-runs.md` — the same day, after the owner woke up and started
using the vault.

The morning's work was verified against the running server. The afternoon's was verified against
**the owner using it**, which found different things: every reversal and every feature below came
from a screenshot or a question, not from a test or a log.

---

## The tag map was reversed within a day, against measurement

#231 had joined tags that share a catalogued problem, to fix the 81-of-83 dust cloud. The owner
opened the graph and asked two questions the edges could not answer: *why is node size the
catalog's, and why can I not click a type and reach my problems?*

Measured before deciding: Obsidian sizes a node by its link count and cannot read frontmatter, so
`implementation` had 29 links of which **2 (7%)** were the owner's problems. It would take 27+
solved implementation problems before the owner's own work matched the catalog's contribution to
that one node. The dust cloud had been an accurate picture of four solved problems; the 510 edges
made the map *look* informative while hiding both signals it existed for.

Reversed in [[decisions/2026-08-13-node-size-is-what-you-solved]] (#241): tags link only to the
problems whose records raised their counts, "never met" moved from isolation to an Obsidian
colour group. Two reversals in two days on one feature — the first decided against a screenshot,
the second against the same screenshot **plus the link counts underneath it**, which is what was
missing the first time.

## A run is not an attempt — four sites in one day

| # | where | symptom |
|---|---|---|
| #235 | `stats` | 7 compile errors in the verdict tally, every one a run |
| #237 | `get_problem` | `submissionCount: 15` where `list_problems` said 8 |
| #241 | node size | (same family: counting what is not the thing) |
| #248 | problem page | `language: python3` beside `verdict: PASS`, from a run that produced no verdict |

Design §5.1 said it once; four places needed telling separately. The rule now lives in
`SubmissionRecord.isSubmission()`, and the counter-practice line — *ask two consumers of the same
data the same question* — found #237 and #248 directly.

## The clock was the container's, not the owner's

`ts: …T05:23:07Z` rendered as `05:23` for a 14:23 KST submit (#243). The instants were correct;
the offset was UTC because no `TZ` reached the container. Fixed with a neutral default (UTC) plus
a startup line saying which clock is in use — and `TRACKER_BACKUP_ZONE`'s hardcoded `Asia/Seoul`
default fell to the same PR, two lines under a comment that already forbade it.

CI then caught what local runs could not: `DailyBackupTest` had been inheriting the Seoul default
while writing fixtures in Seoul terms — asserting the default, not the behaviour. Green on the
author's machine, red on four UTC runners. The zone parameter now has **no default at all**:
`systemDefault()` would have been worse than the hardcoded zone, because a caller that omits it
behaves differently on two machines.

## CI got 15% faster, measured on the runners

The Gradle cache was already everywhere; the gaps were `maxParallelForks` (unset = 1) and
`--rerun-tasks` recompiling Kotlin three times in the repeatability job. Laptop numbers predicted
it, runner numbers confirmed it: wall clock 6m02s → 5m08s, macOS gates −62% (#245/#246). The
first draft of the parallel-safety comment was wrong and grep said so — two tests mutate
JVM-global state and survive only because forks are separate JVMs.

## The vault stopped committing the editor

Four commits carried `.obsidian/`; the 23:00 backup carried **nothing else**, under a message
about records. Option B (#234): ignore the directory, keep `git add --all` intact — narrowing
the staging scope would trade a visible annoyance for a record going uncommitted, the direction
the constitution ranks worst. `progress.md` also joined `log.md` in `merge=union` after five
identical keep-both conflicts in one day (#252), verified by reproducing the conflict.

## The dashboard the design rejected arrived as something else

§5.5's `_dashboard.md` was "aggregation — allowed, unbuilt, no owner" (#227 had sorted the five
phantom notes). What aged badly was the **shape**: a generated note is derived data that goes
stale between writes. An Obsidian Base is a *query* — evaluated live, copies nothing, and becomes
the reader's the moment they edit it.

Seeded by the server when absent (#254/#255), on the owner's suggestion, which beat shipping it
in the template: a template is copied once, so existing repositories would never receive it — the
staleness that hit the vault README twice this same day. Verified both directions: deleted →
written on boot; edited → survived a restart byte-identical.

The English-only guard caught six Korean view names on the way — the same line `ProblemReadme`
already drew between our labels and the data's words.

## The record finally says algorithm or database

The owner asked whether SQL and algorithm were separated. The honest answer was "only by
inference" — `language=mysql`, a part name — and both break on a rename. The fact was already in
`ChannelKey.kind`: the server picks the channel by it before the first frame arrives. One field
threaded through both `SettledCapture` construction sites (#256).

Measured first: the wire value is **`database`, not `sql`** — a guessed vocabulary would have
shipped a word the protocol never uses. And `start` carries `challengeable_type` for both kinds,
so a record that exists always had its kind available; it still comes from the channel, because
the channel is a value we *chose* (strict), not one we were handed (lenient).

## Stale notes, corrected by reading code instead of memos

Four "known and unfiled" items in `goal.md` turned out to be finished work: session expiry
reaches the extension badge end to end, subscription failure demotes health and reaches the
badge, `hintLevel` was already removed, the docker-CI 429 never recurred. The notes had outlived
the work — and the reader (me) had spent the day preaching outside references while treating his
own memos as one.

## What found each thing

| finding | found by |
|---|---|
| node size meant the catalog | the owner opening the graph |
| the language beside the verdict | reading one page end to end |
| the timezone | the owner reading the attempt table |
| the zone-default test | CI's UTC runners |
| four stale to-do notes | grepping code instead of reading memos |
| `database` not `sql` | measuring before naming |

Not one from the suite, which stayed green throughout. The theme has not changed all day; the
sources of outside reference just kept rotating — screenshots, runners, grep, and the owner.
