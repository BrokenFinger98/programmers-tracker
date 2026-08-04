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
├── .claude/commands/           project-scoped wiki commands
├── .githooks/                  push gate — blocks pushes without wiki records
├── .harness/state/             out-of-session memory (goal · progress)
├── docs/
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

## License

MIT
