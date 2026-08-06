---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [catalog, tags, third-party-data, distribution, yagni]
created: 2026-08-06
updated: 2026-08-06
sources: [decisions/2026-08-06-mcp-read-slice]
---

# The problem catalog is built once, by us, and ships in the jar

Date: 2026-08-06 · Status: accepted · Issue: #63

Reverses the refresh cadence in design §5.3 and development-rules §8, and settles a question
those documents never asked: who collects this data, and does it travel with the code.

## Context

Records carry a title, a level and tags. All three come from a catalog of Programmers'
problems, specified as a **689-problem snapshot refreshed once a day** at `.ps/catalog.json`.
It was never built.

Two things made it worth deciding rather than implementing as written.

**The refresh cadence.** A daily sweep is ~250,000 requests a year against somebody else's
site to track a catalogue that changes a few times a year. Whatever the right answer was, it
was not that.

**Programmers' terms.** Their footer refuses 무단 복제 · 배포 · 크롤링 · 스크래핑 of site
content. Most of this project is untouched by that — it observes broadcasts about the user's
own submissions with the user's own session, and fetches the user's own code. A catalogue
sweep is the one part whose request count is driven by *their* catalogue size rather than by
anything the user did, and it is the only part that would put their data in our repository.

## Options considered

- **Daily refresh, as written** — rejected. 250,000 requests a year for data that is nearly
  static, and the cost falls on someone else.
- **On demand: only problems the user actually solves.** Cheapest by far, and the problem
  page is *already* fetched for the user's code, so titles cost nothing extra. Rejected as
  the whole answer because it cannot serve recommendation or per-company profiles (design
  §6.2) — both are about problems the user has **not** solved, which by construction it never
  sees. It survives as the fallback for anything the catalog misses.
- **Each user runs the scan locally** — the shape that keeps third-party data out of this
  repository. Rejected on arithmetic: 536 requests per user, so a hundred users cost
  Programmers 53,600 requests to produce a hundred identical files. The option that looks
  more considerate is the one that generates two orders of magnitude more traffic.
- **Ship ids and our labels, no titles** — considered seriously, as the minimum that keeps
  their text out of the repository. Rejected by the owner on a practical ground that held up:
  a catalog nobody can read cannot be reviewed, and a label cannot be checked without seeing
  what it labels.
- **Build it once, by us, and ship it (chosen).**

## Decision

1. **One scan, ever, not a refresh cycle.** `.ps/catalog.json` and its daily poll are gone.
2. **The result ships** in `src/main/resources/catalog.json` and loads into memory at startup.
   A user runs nothing and fetches nothing.
3. **Titles are included**, with id, level, `partTitle`, acceptance rate and our tags.
4. **Tags come only from solved.ac's published vocabulary**, which also ships
   (`src/main/resources/tag-vocab.json`) — development-rules §8 requires a local replica and
   `.ps/` is gitignored, so until now no clone had one.
5. **A miss is not an error.** The catalog is a snapshot of a catalogue we do not own; a
   problem published later is simply absent, and every lookup answers with absence.
6. **Provenance travels in the file** — how it was collected, at what spacing, with how many
   requests.

## Rationale

The deciding argument is total traffic, and it points the opposite way from the intuition
that shipping is the more aggressive choice:

| | requests to Programmers |
|---|---|
| daily refresh, per user | ~250,000 / year |
| one local scan, per user | 536 × every user |
| **one scan, shipped** | **543, once, ever** |

What ships is 689 ids, titles and levels — short factual identifiers, publicly visible in
URLs and search results — plus labels that are our own work. **No problem statements, no
example data, no test cases.** The statements were read to produce the labels and then
discarded; they never entered the repository.

The labelling itself is not the server interpreting anything, which CLAUDE.md forbids. It was
done once, offline, by models reading statements — and the server only reads the result.

## Accepted costs

- **We distribute an index of somebody else's catalogue.** Minimised, not eliminated. Titles
  and ids are theirs. The judgement is that a static index of public identifiers, carrying no
  problem content, is proportionate to the traffic it saves — but it is a judgement, not a
  legal opinion, and the README's standing commitment applies: if Programmers ask us to stop,
  we comply.
- **It goes stale and nothing refreshes it.** A new contest is missing until someone
  regenerates the file. The on-demand path (#59) covers titles for anything absent; tags for
  a new problem are simply unknown.
- **The labels are ours and therefore arguable.** 434 of 536 were labelled with high
  confidence, 81 medium, 21 low — the confidence is stored per entry so a consumer can weigh
  it, and a wrong label quietly skews a weakness profile.
- **The jar grows by ~180 KB.**
- **Reproducing it is manual.** The collection was a one-off script, not a maintained command,
  so regenerating it is real work. Deliberate: a maintained scanner is a crawler with a
  cron-shaped hole waiting to be filled in.

## Outcome

Built 2026-08-06. 689 problems: 536 classified by reading their statements, 153 taken from a
`partTitle` that already names the technique (`SELECT`, `해시`, `깊이/너비 우선 탐색(DFS/BFS)` …).

Collection, measured: metadata 7 requests with every response validated; statements 536
requests at 1.6-second spacing with **zero failures**; labelling **zero requests**, eight
workers reading files already on disk.

Validated before shipping: 536/536 classified, no id missing or duplicated, no tag outside
the 229-tag vocabulary. `ClasspathProblemCatalogTest` asserts those properties against the
**real shipped resource** rather than a fixture, because the file is the artifact — a fixture
would test the loader and say nothing about what actually ships.

One measurement worth keeping: the list API **throttles and fails as a 200 carrying an HTML
error page**, not a 429 (protocol §14). Two of seven sequential requests at 2-second spacing
came back as `서비스 접속 오류`. Anything that fetches from them must validate the body;
the collection script did, which is why the statement pass had no silent holes.

Related: [[decisions/2026-08-06-mcp-read-slice]] · [[concepts/assumption-vs-measurement]].
