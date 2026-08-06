# programmers-tracker

A local server that watches your Programmers grading stream and records **every `run` and
every `submit` — failures included** — into a git repository you own.

> **Status: Phase 1, mid-build.** The design and the protocol reverse engineering are
> finished. The capture half is built; the analysis half is not.
> **[What works today](#what-works-today) is the only section of this file that states what
> is implemented.** Everything after it describes the design, and that table says which
> parts of the design exist.

---

## Why build this

Programmers streams grading results in the moment and then lets them go. **There is no API to query
past submissions.** All that remains is "solved / not solved" and per-day attempt counts.

This is what an actual account record looks like:

```
2025   total attempts 449   problems solved 43
```

**Of 449 attempts, only the 43 successes are knowable — why the other 406 failed is already lost.**
Existing tools like BaekjoonHub only fire on accepted answers, so they structurally cannot record failure.

The learning signal is in the failures, but the record keeps only successes. This closes that gap.

---

## What works today

Install it now and this is what you get. Section numbers point at the
[design document](docs/superpowers/specs/2026-08-04-programmers-tracker-design.md); nothing
marked *designed* exists in `src/`.

| | State |
|---|---|
| Capture every `run` and `submit` on a watched problem, live from the grading stream | **built** |
| Five measured verdicts — pass · wrong · timeout · runtime error · compile error | **built** |
| A grading we could not classify stays `UNKNOWN` instead of being filed as a neighbour | **built** |
| Per-testcase results, attempt number, time spent | **built** |
| The original frames kept verbatim beside the record | **built** |
| Records written to your own git repository — committed, pushed on a pass, backed up daily | **built** |
| One instance per record repository, enforced at startup | **built** (with a measured macOS caveat — [`bootstrap.md`](docs/bootstrap.md)) |
| Telling the server which problem you are on | **built** — one `curl` per problem |
| A browser sensor that tells it for you | designed · §8 |
| The failing code stored alongside the record | **built** |
| A per-problem page, and diffs between attempts | **built** |
| Problem titles and tags from a catalog | **built** — 689 problems, shipped |
| MCP server — Claude · Cursor · a local LLM reading the records | **built** — three read tools ([`mcp.md`](docs/mcp.md)) |
| Weakness by tag · review queue · passed-but-slow · per-company profiles | designed · §6 |

One consequence of that table is worth stating outright, because it changes what the first
hour with this tool feels like: **you register each problem by hand**, and again after a
restart or a language-tab switch — a submission on an unregistered problem is lost.
[`docs/bootstrap.md`](docs/bootstrap.md) walks the whole gap.

---

## Get started

You need Docker, a Programmers login, and a git repository of your own to keep the records in.

```bash
git clone https://github.com/BrokenFinger98/programmers-tracker.git
cd programmers-tracker

cp -R template/ps-records ~/ps-records && git -C ~/ps-records init   # your records — yours, and separate
mkdir -p .ps && printf '%s' 'YOUR__session_production_COOKIE' > .ps/session

cp .env.example .env    # set TRACKER_RECORD_REPO, GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL
docker compose build && docker compose up -d   # build first: up alone reuses a stale image
```

**[→ Full walkthrough: `docs/bootstrap.md`](docs/bootstrap.md)** — where to find the cookie,
how the `/watch` token works, and what a working install looks like. Read it; the five lines
above will not get you a record on their own.

The server also runs natively on a JDK 25 (`./gradlew bootRun`) — the guide covers both.

---

## How it is designed to work

```
Programmers web          ← browse · search · write · run · submit stays right here
    │
    ├─ sensor extension ──────▶ only the "currently viewing this problem" signal
    │
    └─ ActionCable ───▶ the server subscribes to the same channel → results arrive as broadcasts
                              │
                              ├─ verdict resolution · diff between attempts
                              ├─ record to ps-records/ + git commit
                              └─ MCP exposure ──▶ Claude · Cursor · local LLM
```

That is the full design. The table above says which of those boxes exist today.

**The server sends nothing to Programmers.** It subscribes to the same channel and only listens.
Submission is done by the user, directly in the browser.

Programmers grading is not REST but **Rails ActionCable WebSocket**, and we exploit the fact that
every client subscribed to the same channel receives identical messages.
The full protocol story: [`docs/programmers-protocol.md`](docs/programmers-protocol.md).

---

## How it differs from existing tools

What the two tools are built to do. For what is implemented, the table above is the authority.

| | BaekjoonHub | programmers-tracker |
|---|---|---|
| What triggers a record | An accepted submission | Every `run` and every `submit` |
| Failures | Never recorded — it structurally cannot | The whole point |
| Where the data comes from | Scraping the results page | The grading stream itself |
| Per-testcase detail | Whatever the page renders | Every testcase frame, kept verbatim |
| Failure type | — | wrong · timeout · runtime · compile |
| Attempt count · time spent | — | Recorded per submission |
| Code, diffs, AI analysis | — | Designed on top of that record |

---

## What the record is designed to tell you

The capture format exists to answer these. They are §6 of the design; the table above tracks
which have been built.

- **How** you mostly die — logic errors, timeouts, or simple mistakes
- **First-submission pass rate** — the metric that matters most in the real thing
- Weaknesses by type (against the solved.ac tag vocabulary)
- **Passed-but-slow problems** — solutions that dodged the intended approach
- Repeated mistakes — an AI reads the diffs between attempts and points them out
- When to review — confidence-based spaced repetition
- Per-company problem tendencies — 98 past Kakao problems aggregated by tag

---

## Structure

```
.                               (this repository)
├── CLAUDE.md                   development constitution — prohibitions · quality gates · state operations
├── Dockerfile                  multi-stage image — JVM 25, no configuration baked in
├── compose.yaml                mounts your records and your cookie; publishes to loopback only
├── .env.example                copy to .env — the settings that have no default
├── .claude/skills/             project-scoped skills (issue · commit · pull-request · wiki)
├── .githooks/                  push gate — blocks pushes without wiki records
├── .harness/state/             out-of-session memory (goal · progress)
├── docs/
│   ├── bootstrap.md              setup walkthrough — start here to run it
│   ├── programmers-protocol.md   protocol reverse engineering (measured evidence)
│   ├── development-rules.md      coding conventions
│   ├── llm-wiki/                 development process records
│   └── superpowers/specs/        design documents
├── scripts/                    check · test · build · guards — CI runs these same files
├── src/                        Kotlin + Spring Boot
└── template/ps-records/        initial structure of the record repository
```

Your records live in a **separate repository that you create** (`cp -R template/ps-records …`
in *Get started* above) and the server only writes into it. Open it as an Obsidian vault once
the derived pages of §5.5 exist.

> Once after cloning: `git config core.hooksPath .githooks` — activates the push gate.
> Claude Code sets this up automatically via a session-start hook.

---

## Principles toward Programmers

- This tool is **for personal learning records** and is used only on your own account
- **It provides no auto-submission.** The user submits directly in the browser;
  the server merely observes and records the results
- The server sends Programmers two kinds of request, both at the level of what a browser
  already does: the channel subscription, and a problem-page fetch to recover the code you
  wrote (§4.4). **It never fetches a catalog** — problem titles, levels and tags ship inside
  the jar, collected once and for everybody rather than once per install
  ([`the decision`](docs/llm-wiki/wiki/decisions/2026-08-06-shipped-problem-catalog.md))
- If Programmers asks us to stop, we comply

Since this tool uses a private protocol, the judgment and responsibility for using it rest with the user.

---

## Contributing

Issues and PRs are welcome — read [CONTRIBUTING.md](CONTRIBUTING.md) first
(issue-first, squash-only, English-only, and a push gate that asks you to
record decisions). AI-assisted contributions are explicitly welcome.

---

## License

[MIT](LICENSE). The license carries the standard warranty and liability disclaimer;
the private-protocol caveat is the paragraph under *Principles toward Programmers* above.
