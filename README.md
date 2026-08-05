# programmers-tracker

A local server that records your Programmers solving process **completely, per submission**, and
exposes it over MCP so any AI can diagnose weaknesses, recommend problems, and manage reviews.

> ⚠️ Under development. Design and protocol reverse engineering are complete; implementation is in progress.

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

## Get started

You need Docker, a Programmers login, and a git repository of your own to keep the records in.

```bash
git clone https://github.com/BrokenFinger98/programmers-tracker.git
cd programmers-tracker

cp -R template/ps-records ~/ps-records && git -C ~/ps-records init   # your records — yours, and separate
mkdir -p .ps && printf '%s' 'YOUR__session_production_COOKIE' > .ps/session

cp .env.example .env    # set TRACKER_RECORD_REPO, GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL
docker compose up -d
```

**[→ Full walkthrough: `docs/bootstrap.md`](docs/bootstrap.md)** — where to find the cookie,
how the `/watch` token works, what a working install looks like, and what is genuinely
missing today. Read it; the five lines above will not get you a record on their own.

The server also runs natively on a JDK 25 (`./gradlew bootRun`) — the guide covers both.

---

## How it works

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

**The server sends nothing to Programmers.** It subscribes to the same channel and only listens;
code is fetched from the problem page. Submission is done by the user, directly in the browser.

Programmers grading is not REST but **Rails ActionCable WebSocket**, and we exploit the fact that
every client subscribed to the same channel receives identical messages.
The full protocol story: [`docs/programmers-protocol.md`](docs/programmers-protocol.md).

---

## How it differs from existing tools

| | BaekjoonHub | programmers-tracker |
|---|---|---|
| When it records | Accepted only | **Every `run` · `submit`** |
| Failing code | ✗ | ✅ |
| Attempt count · time spent | ✗ | ✅ |
| Failure type distinction | ✗ | ✅ wrong / timeout / runtime / compile |
| Per-testcase results | DOM scraping | **Original stream** |
| Diff between attempts | ✗ | ✅ |
| AI analysis | ✗ | ✅ MCP |

---

## What you get to know

- **How** you mostly die — logic errors, timeouts, or simple mistakes
- **First-submission pass rate** — the metric that matters most in the real thing
- Weaknesses by type (against the solved.ac 180-tag vocabulary)
- **Passed-but-slow problems** — solutions that dodged the intended approach
- Repeated mistakes — an AI reads the diffs between attempts and points them out
- When to review — confidence-based spaced repetition
- Per-company problem tendencies — 98 past Kakao problems aggregated by tag

---

## Structure

```
programmers-tracker/            (this repository)
├── CLAUDE.md                   development constitution — prohibitions · quality gates · state operations
├── Dockerfile                  multi-stage image — JVM 25, no configuration baked in
├── compose.yaml                mounts your records and your cookie; publishes to loopback only
├── .env.example                copy to .env — the settings that have no default
├── .claude/commands/           project-scoped wiki commands
├── .githooks/                  push gate — blocks pushes without wiki records
├── .harness/state/             out-of-session memory (goal · progress)
├── docs/
│   ├── bootstrap.md              setup walkthrough — start here to run it
│   ├── programmers-protocol.md   protocol reverse engineering (measured evidence)
│   ├── development-rules.md      coding conventions
│   ├── llm-wiki/                 development process records
│   └── superpowers/specs/        design documents
├── src/                        Kotlin + Spring Boot
└── template/ps-records/        initial structure of the record repository

ps-records/                     (separate repository — created by the server)
└── open it as an Obsidian vault for a GUI over dashboards · weakness analysis · review queue
```

> Once after cloning: `git config core.hooksPath .githooks` — activates the push gate.
> Claude Code sets this up automatically via a session-start hook.

---

## Principles toward Programmers

- This tool is **for personal learning records** and is used only on your own account
- **It provides no auto-submission.** The user submits directly in the browser;
  the server merely observes and records the results
- The only requests the server sends are channel subscription · problem page fetch · catalog fetch,
  all at the same level as what a browser does
- Catalog polling never exceeds once per day
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
