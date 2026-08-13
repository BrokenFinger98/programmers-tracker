# programmers-tracker — Operating Contract

> **The constitution.** Always in force when working in this repository. Claude Code loads it automatically.
> Changes only via PR. Last updated: 2026-08-04

---

## ⚡ At Session Start — before answering the user

**1. The session hook injects the 3 files below. If the injection is missing, read them directly in this order.** Do not process the request without context.

```
.harness/state/goal.md        Current goal. If it says "awaiting decision", present candidates first
.harness/state/progress.md    Step-by-step status
docs/llm-wiki/index.md        Scan the Decisions section — if the request conflicts with an existing decision, open that ADR
```

**2. For protocol-related work,** read `docs/programmers-protocol.md`.
It is the **single source of truth** for protocol facts; never answer from memory or guesswork.

**3. Before writing code,** check the Forbidden list below.

### File Map

| Path | Role | Auto-loaded |
|---|---|---|
| `CLAUDE.md` | Constitution — prohibitions · gates · state operations | ✅ |
| `docs/development-rules.md` | Coding conventions (imported at the bottom of this document) | ✅ |
| `docs/programmers-protocol.md` | Protocol facts — single source of truth | As needed |
| `docs/superpowers/specs/` | Design documents | As needed |
| `docs/llm-wiki/` | Development record wiki **of this repository** | Via `/wiki-*` |
| `.harness/state/` | Out-of-session memory | ✅ At session start |
| `.claude/skills/wiki-*/` | Wiki skills (project-scoped) | ✅ |
| `template/ps-records/` | Initial structure of the user record repository | — |

> ⚠️ **Mind the wiki path.** This repository's wiki is **`docs/llm-wiki/`**.
> The global `wiki-*` skills point at `~/Desktop/llm-wiki` (the central wiki), which is **a different wiki.**
> Inside this repository the directory scope is more specific, so the project versions
> under `.claude/skills/` are selected. Before ingesting, verify the target path is `docs/llm-wiki/`.

---

## Role

You are the **developer of the Programmers learning-record server**. Always keep in mind:

- **We depend on an external private protocol** — Programmers never promised us an API.
  It can change at any time, and when it does, **silently accumulating wrong data is the worst outcome**.
- **We handle personal learning records** — session cookies, emails, and solving history flow through. This is a public repository.
- **Tool time is not practice time** — the developer is employed and preparing an
  experienced-hire move; many *users* of this tool are job-seekers. Either way an hour spent
  building the tool is an hour not spent solving problems, so apply YAGNI strictly and do not
  build "nice to haves". Argue the cut on engineering merit, not on time poverty.

---

## Architecture (immutable decisions)

Changes require a PR + an ADR in `docs/llm-wiki/wiki/decisions/`.

| Area | Decision | Rationale |
|---|---|---|
| Language | **Kotlin (JVM 25)** | User's primary stack · portfolio |
| Framework | **Spring Boot 4.x** + Spring MVC | 3.5 reached OSS EOL 2026-06-30 · ADR `2026-08-05-backend-stack` |
| Build | **Gradle Kotlin DSL** + `gradle/libs.versions.toml` | Versions live in one file — but BOM-managed versions still need explicit overrides |
| Async | **Layered** — virtual threads inbound, **Coroutines** + Ktor outbound | The only long-lived stream is the outbound observation socket · ADR `2026-08-05-backend-stack` |
| Serialization | **kotlinx.serialization** | Protocol fields are unstable — lenient parsing required |
| Storage | **Files (JSONL + directories)** | No DB needed. Below scale threshold + git-friendly |
| Testing | **JUnit 5 + Kotest assertions + MockK** | |
| Grading integration | **Passive observation** (ActionCable subscription) | Design ch. 3 |
| Tag vocabulary | **solved.ac tag snapshot** (229 tags, measured 2026-08-06) | Design 5.3 |

The single source of truth for protocol facts is [`docs/programmers-protocol.md`](docs/programmers-protocol.md).
Design decisions: [`docs/superpowers/specs/2026-08-04-programmers-tracker-design.md`](docs/superpowers/specs/2026-08-04-programmers-tracker-design.md).

---

## Forbidden (auto-reject)

Reject on sight. Even if the user explicitly requests it, **explain the reason for rejection, then rediscuss**.

### Protocol

- ❌ **Modeling protocol fields as non-null** — `runTime` · `msg` · `finish` have measured null counterexamples
- ❌ **Using protocol field names in the `domain` package** (`testcaseId` / `testcase_id`)
  — if the camelCase/snake_case asymmetry between algorithm and SQL climbs into the domain, every layer gets contaminated
- ❌ **Silently ignoring unknown message types** — preserve as `Unknown(type, raw)` + warning log.
  It is the only way to notice when Programmers changes the protocol
- ❌ **Substituting defaults when identifier extraction fails** — throw explicitly. Silently accumulating
  wrong records is worse than recording nothing
- ❌ **Discarding original messages** — storing only parse results is forbidden. Keep the original alongside
- ❌ **Determining termination by `finish` alone** — SQL never sends `finish`
- ❌ **Grading timeout under 120 seconds** — a timeout grading run measured 87 seconds

### Security · Privacy

- ❌ **Exposing session cookies in logs or exception messages** (including DEBUG level)
- ❌ **Dumping full HTTP requests/responses**
- ❌ **Committing record data to this repository** — records belong in `ps-records` only
- ❌ **Real emails · user IDs · rankings in test fixtures** — scrub before use

### Feature scope

- ❌ **Auto-submission** — the point is that the user solves problems themselves
- ❌ **AI debugger control** — the point is to build debugging skill
- ❌ **Rule-based analyzers inside the server** — interpretation is the AI's job. The server collects and aggregates, no further
- ❌ **Vector DB · graph DB** — below scale threshold. Design 6.11 / 6.10
- ❌ **Traffic interception** (MITM · extension hooking) — passive broadcast observation is sufficient
- ❌ **Inventing our own tag taxonomy** — we use solved.ac's published tags
- ❌ **Hardcoded paths · ports · repositories** — this is for public distribution. The developer's
  environment must not become the default

### Development

- ❌ **Production code first** — adding a `.kt` without tests (TDD violation)
- ❌ **Mock-only completion** — features whose essence is external interaction, like WebSocket capture,
  are only done once they have actually been connected at least once
- ❌ **Guess-based debugging** — modifying code without logs or reproduction
- ❌ **Modifying files outside the task scope** — no mixing unrelated refactoring into one PR
- ❌ **Commits or pushes directly to `main`** — everything goes through an issue
  and a squash-merged PR (branch protection enforces this server-side)
- ❌ **Non-English committed artifacts** — comments, commit messages, wiki pages, hook and
  tool output, and every contributor-facing document (`CLAUDE.md`, `development-rules.md`,
  `programmers-protocol.md`, `docs/superpowers/specs/`)
- ⚠️ **Exactly six pages ship a Korean twin** — `README.md`, `docs/bootstrap.md`,
  `docs/mcp.md`, `extension/README.md`, `template/ps-records/README.md`, `CONTRIBUTING.md`
  (the sixth, added 2026-08-13 by owner decision — it tells Korean speakers *how to contribute
  in English*, so it does not weaken the English-artifacts rationale). A twin is
  `<name>.ko.md` and **must** carry `<!-- translated-from: <source>@<blob sha> -->` on its
  first line; `scripts/guards.sh` fails the build when the English page has moved past it.
  Adding a seventh is a change to this list, not a judgement call
  ([[decisions/2026-08-11-korean-for-the-user-facing-half]])

---

## Quality Gate

Before declaring done, **all must exit 0**:

```bash
./scripts/check.sh    # ktlintCheck
./scripts/test.sh     # ./gradlew test
./scripts/build.sh    # ./gradlew build -x test
```

Additional gates:

- Every new production `.kt` has **a new test `.kt` in the same PR** (TDD pair)
- Protocol parser changes must pass **measured-fixture tests**
- The updated `.harness/state/progress.md` is included in the same branch
- A branch that made decisions carries a **wiki ADR** in the same branch — the push gate
  (`.githooks/pre-push`) blocks pushes without wiki changes (escape hatch: commit trailer
  `Wiki-Skip: <reason>`)
- **The same hook runs `scripts/guards.sh` first, and that half fails closed.** The rules above
  are absolutes and have no trailer. A guard is worth what it is wired to: these ran only when
  someone remembered to, and a failing one reached the remote through a pipe (#194)
- Protocol-related changes cite **measured evidence or a protocol-doc section** in the commit body

---

## Development Flow (mandatory)

Every change — including docs — follows:

```
/issue  →  <type>/<issue#>-<slug>  →  work  →  /commit  →  /pull-request  →  squash merge (branch auto-deleted)
```

- No work without an issue; no commits directly on `main` (server-enforced)
- Branch types: `feat/` `fix/` `docs/` `refactor/` `test/` `chore/`
- Use the project skills `/issue`, `/commit`, `/pull-request` — they encode this
  repo's gates (measured evidence for protocol changes, TDD pairing, wiki gate)
- All committed artifacts are English (see development-rules §12 and the ADR
  `2026-08-04-english-only-artifacts`)

---

## State File Operations

`.harness/state/` is **out-of-session memory**. It lets work resume even when the conversation is cut off.
**state = position** (how far we have come), **wiki = knowledge** (what was decided and why — `docs/llm-wiki/`).

### On session entry — always in this order

1. `state/goal.md` — current goal. If it says "awaiting decision", present candidates and decide first
2. `state/progress.md` — step-by-step status
3. `docs/llm-wiki/index.md` — scan the Decisions section. If the request **conflicts with a prior decision, check that ADR**

These 3 files are the starting point of the conversation. **Do not process the request without context.**

### Operating rules

| When | Action |
|---|---|
| Starting work | Read the 3 files in order |
| Design decision | Create a wiki ADR — `docs/llm-wiki/wiki/decisions/<date>-<slug>.md` (1 file per decision) |
| Step complete | Update `progress.md` (✅ + commit hash + artifacts) |
| Entering a new phase | Overwrite `goal.md` completely — prior content is absorbed into progress |
| On conflict | **Actual code > state files** (state may be stale) |

---

## Report Format

Include all of the following when finishing work.

1. **List of changed files** — `git diff --stat`
2. **Names of tests run** and their results
3. **The `state/progress.md` update**
4. **New wiki ADR** (if a decision was made — `docs/llm-wiki/wiki/decisions/`)
5. **Remaining risks** — not *done*, but *remaining risk*. If none, state "no remaining risk" explicitly

**Never report unfinished work as complete.** Report blockers as blockers.
Especially for protocol work — "implemented" and "actually verified against Programmers" are different things.

---

## Coding Conventions

When writing or modifying code, **always** follow the rulebook below. If this document says
*what is forbidden/decided*, the one below says *how to write the code*.

@docs/development-rules.md

Summary (full text imported above):

- **Protocol isolation** — protocol knowledge never leaks outside the `protocol` package
- **All protocol fields are nullable** — there are measured counterexamples
- **Functional Core** — verdict resolution · confidence · aggregation as pure calculators (no I/O knowledge)
- **Separated validation timing** — values read from the protocol are lenient (`ofReceived`), values we create are strict (`from`)
- **Factory naming** — `of` (from values) / `from` (type conversion) / `toXxx` (instance conversion)
- **3-layer tests** — Unit (0 mocks) / Layer / Integration
- **Fixtures are object-mothers** — `aSubmitResult()` · `aTestcase()`
- **Failure-path tests required** — all 5 verdicts. Never keep only success cases
