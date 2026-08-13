# Bootstrap — from nothing to a first record

**[한국어](bootstrap.ko.md)**

This walks you from a clean machine to a server that is watching a problem and writing a
record when you submit. It assumes you have never seen this repository.

If a step does not apply to you, it says so; do not skip one because it looks optional.

> **Read this before you start.** The last section, *[What you cannot do
> yet](#what-you-cannot-do-yet)*, lists what is genuinely missing today. Nothing below
> pretends otherwise, but you should know the shape before you invest the time. The shortest
> version: a problem the server was never told about records **nothing**, silently, and the
> sensor extension only removes that burden on a browser profile it is loaded into.

---

## 0. What this actually is

A **resident local process**. It stays running while you solve problems on Programmers,
subscribes to the same grading channel your browser does, and writes every `run` and
`submit` — including the failures — into a git repository you own.

It never submits anything for you and it sends Programmers nothing that a browser does not
already send.

Two things live outside it and must be yours:

| | What it is | Where it lives |
|---|---|---|
| **The record repository** | Your solving history. A normal git repository. | A path you choose, outside this repository |
| **The session cookie** | Your Programmers login. A credential. | A file you create, never committed |

---

## 1. Install

Pick one. They produce the same result; Docker is the shorter path.

**Docker** — [Docker Desktop](https://www.docker.com/products/docker-desktop/) on macOS or
Windows, or Docker Engine plus the Compose plugin on Linux. Nothing else. You do not need
a JDK.

```bash
docker --version && docker compose version
```

**Native** — a JDK 25 ([Temurin](https://adoptium.net/temurin/releases/?version=25)) and
`git` on your `PATH`. The Gradle wrapper handles the rest.

```bash
java -version   # must say 25
git --version
```

Then clone this repository. Everything below runs from its root.

```bash
git clone https://github.com/BrokenFinger98/programmers-tracker.git
cd programmers-tracker
```

---

## 2. The record repository — the server creates it

This is your data. It is deliberately **not** inside this repository — solving records
never enter the tool's own repository, and keeping them separate is what lets you make
yours private while the tool stays public.

**There is nothing to run.** On first start the server creates the directory
(`~/ps-records` unless `TRACKER_RECORD_REPO` in `.env` says otherwise), runs `git init`,
and seeds a README, an Obsidian dashboard and the ignore rules. The startup log says
`Records live at <path>` so the location is never silent.

For an off-machine backup, put a GitHub token in `.env`:

```bash
# .env
GITHUB_TOKEN=github_pat_…
```

On the next start the server asks GitHub who the token belongs to, creates a **private**
repository named after the record directory (private is hardcoded and verified from the
response — if GitHub ever answers with a public repository, nothing is wired), sets it as
`origin`, stores the credential owner-only inside the records' gitignored `.ps/`, and
pushes. You may delete the `.env` line after that first boot; the stored credential
carries pushes from then on. Revoking the token stops pushes until you provide a new one.

An existing repository — initialised by hand, or wired to any remote — is never rewired:
the server adds what is missing and changes nothing that exists. For a non-GitHub remote,
skip the token and `git remote add origin <url>` yourself, with whatever credentials that
host wants.

**Installed back when pushes went over SSH?** Point the remote at HTTPS
(`git -C ~/ps-records remote set-url origin https://github.com/<you>/<repo>.git`), put the
token in `.env`, restart, and delete the old key mount from `compose.override.yaml` — SSH
was retired in #258.

You can also skip remotes entirely. Records are still written and committed locally; only
the push is lost, and it is retried rather than dropped.

---

## 3. Get your session cookie

The server subscribes to Programmers' grading channel as you. That needs one cookie,
`_session_production`.

1. Log in to <https://school.programmers.co.kr> in your browser.
2. Open DevTools (`F12`, or `Cmd+Option+I` on macOS).
3. **Application** (Chrome) or **Storage** (Firefox) → **Cookies** →
   `https://school.programmers.co.kr`.
4. Find `_session_production` and copy its **Value**.

It is `HttpOnly`, so `document.cookie` in the console will not show it. DevTools is the way.

Write it into `.ps/session`:

```bash
mkdir -p .ps
printf '%s' 'PASTE_THE_VALUE_HERE' > .ps/session
chmod 600 .ps/session
```

`.ps/` is gitignored in full and a repository guard fails the build if anything under it is
ever committed. The file holds the bare value; the tool adds the `_session_production=`
prefix itself.

**Treat it as a password.** It is your login. It expires — when it does, subscriptions
start failing and you repeat this step.

---

## 4. Start the server

### With Docker

```bash
cp .env.example .env
```

Edit `.env`. Two things are required and have no default, because neither is ours to guess:

```bash
TRACKER_RECORD_REPO=/absolute/path/to/ps-records   # from step 2, written out in full — ~ is not expanded
GIT_AUTHOR_NAME=Your Name
GIT_AUTHOR_EMAIL=you@example.com
```

git refuses to commit without an identity, so leaving those blank gets you a server that
records to disk and never commits. Compose stops with a message rather than starting one.

On **Linux only**, also match the owner of your record directory so the container can write
it — Docker Desktop on macOS and Windows remaps ownership and needs neither:

```bash
echo "TRACKER_UID=$(id -u)" >> .env
echo "TRACKER_GID=$(id -g)" >> .env
```

Then:

```bash
docker compose up -d --build
docker compose logs -f
```

> **`--build` is not optional, it is the default you want.**
> Plain `docker compose up` reuses an image that already carries the tag and does **not**
> rebuild, so you keep running the code you built last time — and the symptom is behaviour
> that quietly does not happen, or an error naming a field the current code no longer has.
> Measured 2026-08-10: a freshly written extension answered
> `400 INVALID_REQUEST — challengeableId is missing` against a four-day-old container.
> The first log line tells you which build you are on:
>
> ```
> Running build 0.0.1-SNAPSHOT — compiled 2026-08-06 15:32:45 KST from commit unknown.
> ```
>
> If that timestamp predates your last pull, you are on a stale image.

### Natively

```bash
export TRACKER_RECORD_REPO=~/ps-records
./gradlew bootRun
```

### About the port, and one thing worth understanding

The server listens on **port 8080 of your machine's loopback interface only**. Nothing on
your network can reach it. This matters more than it usually would: the process holds your
live session cookie in memory and can push to your GitHub.

Change the port with `TRACKER_PORT` if 8080 is taken — in `.env` for Docker, or as an
environment variable for a native run.

> **A bind address inside a container namespace is not the same control as a bind address
> on a host.** A container has its own network stack, and `docker -p` forwards to the
> container's ethernet address, never to its loopback — so a server bound to `127.0.0.1`
> inside a container is unreachable from your browser, not merely protected. `compose.yaml`
> therefore binds `0.0.0.0` *inside* the container and publishes the port as
> `127.0.0.1:8080:8080`, and it is that **publish** address that keeps `/watch` off your
> LAN. If you ever shorten it to `8080:8080`, you have exposed the whole thing.
>
> Residual, stated plainly: any other container sharing this one's Docker network can reach
> it whatever the publish address says. The service runs on its own network and nothing
> else joins it, and the token in the next step is the second layer.
>
> Full reasoning: [the container network posture
> ADR](llm-wiki/wiki/decisions/2026-08-06-container-network-posture.md).

---

## 5. Find your watch token

Loopback is shared with every other program on your machine and every page open in your
browser, so the one endpoint that accepts commands — `POST /watch` — requires a token.

**You do not create it.** On first start the server generates a 256-bit token and saves it
owner-only, and it says where:

```
INFO ... c.b.tracker.adapter.web.WatchToken : Generated a local /watch token at .ps/watch-token — paste it into the extension.
```

Read it:

```bash
cat .ps/watch-token
```

It persists across restarts on purpose — a token that changed every run would silently
reject every heartbeat. The value is never logged, only its path. To pin your own instead,
set `TRACKER_WATCH_TOKEN`; there is no way to turn the check off.

---

## 6. Tell the server which problem you are on

The server only observes channels it has been told about, so this step is what makes a
submission get recorded at all.

### With the sensor extension

Install [`extension/`](../extension/README.md) unpacked — `chrome://extensions` → Developer
mode → **Load unpacked** — then open its options and paste the token from step 5. After
that it announces every problem you open, re-announces on a language-tab switch, and
re-registers itself after a server restart. The toolbar badge says whether the server
accepted: green is watching, orange means no token yet, red carries the server's own error.

It was loaded and watched working on 2026-08-10 — the badge read
`watching lesson 181947 in java (refreshed)` against a live problem page. Its README says
exactly what is measured and what is not.

### By hand, without the extension

No DevTools needed — the two things the server cannot work out for itself are the problem
number, which is the last part of the URL, and the language tab you have open. It reads the
rest off the problem page.

```bash
curl -X POST http://127.0.0.1:8080/watch \
  -H "X-Tracker-Token: $(cat .ps/watch-token)" \
  -H 'Content-Type: application/json' \
  -d '{"lessonId":120803,"language":"java"}'
```

Worth knowing even with the extension installed: it is how you check the server directly
when the badge says something you do not expect.

A success looks like this:

```json
{"status":"started","lessonId":120803,"language":"java"}
```

`{"status":"refreshed",...}` means it was already watching that problem. Repeating the call
is safe.

Re-send it when you switch the language tab (a different tab is a different channel) and after restarting the
server. Yes, this is manual — see [What you cannot do
yet](#what-you-cannot-do-yet).

---

## 7. What "it is working" looks like

**On start**, the log ends with these, in this order:

```
Tomcat started on port 8080 (http) with context path '/'
Started TrackerApplicationKt in 1.046 seconds
Startup reconciliation: ReconcileReport(recorded=0, duplicates=0, failed=0, skippedLines=0)
```

With Docker, `docker compose ps` shows the container as `healthy` within about a minute.

**When you press 채점하기 (submit)** on the problem you registered in step 6, the server
observes the grading live and — once it settles — writes into your record repository:

```
ps-records/
├── log/submissions.jsonl                        one line per grading, the authority for attempt numbers
└── problems/120804-<title>/
    ├── README.md                                attempt history
    └── attempts/
        ├── 001.java                             the code you submitted
        └── 001.raw.jsonl                        the original frames, kept verbatim
```

Check it:

```bash
cd "$TRACKER_RECORD_REPO" && git log --oneline -3 && tail -1 log/submissions.jsonl
```

A `submit` that passes is committed and pushed immediately. Everything else is committed and
carried up by the daily backup (23:00 in `TRACKER_BACKUP_ZONE`, caught up at the next start
if the machine was asleep).

**A `run` writes no attempt file.** That is correct — runs ride along with the next submit.

### When it is not working

| What you see | What it means |
|---|---|
| `401 {"error":"UNAUTHORIZED"}` | Wrong or missing `X-Tracker-Token`. Re-read `.ps/watch-token`. |
| `400 {"error":"INVALID_REQUEST","field":"..."}` | That field was missing from the page. Programmers changed its markup, or you ran the snippet off a problem page. |
| `503 {"error":"WATCHER_SATURATED"}` | Eight problems are already being watched and all are mid-grading. Wait, or restart. |
| `curl: (7) Failed to connect` | The server is not running, or `TRACKER_PORT` differs. |
| `Session file not found` | Step 3 was skipped, or `TRACKER_SESSION_FILE` points elsewhere. |
| `not a git repository` warning at start | `TRACKER_RECORD_REPO` is not pointing at a git repository. Records are still written; nothing is committed. |
| `git push failed with 128: ... No configured push destination` | No remote. Expected if you skipped that part of step 2. |
| `git reconcile failed with 128: Author identity unknown` | `GIT_AUTHOR_NAME` / `GIT_AUTHOR_EMAIL` are unset. Records are written but never committed. |
| You submitted and nothing was recorded | The problem was not registered via `/watch`, or the server was down at that moment. **That grading is gone** — it cannot be recovered after the fact, by design. |

---

## What you cannot do yet

Stated plainly, because finding these out by trial is worse.

- **A problem you never registered records nothing, silently.** The sensor extension
  ([`extension/`](../extension/README.md)) exists to remove that burden and was verified in
  a browser on 2026-08-10, but it only announces pages it is loaded on. If the badge is not
  green, nothing is being watched — keep step 6's manual route in reach.
- **The MCP server exposes six tools, not the design's twenty.** `submissions`,
  `get_problem`, `stats`, `list_problems`, `review_queue` and `slow_passes` are built and
  connectable today — see [`mcp.md`](mcp.md) for the client configuration. What is still
  missing is the rest of the analysis half: warmup diagnosis, exam mode, per-company profiles,
  and anything that writes.
- **Pushing authenticates with `GITHUB_TOKEN`** — the server stores it owner-only inside the
  records' gitignored `.ps/` and git reads it from there; nothing is mounted. Without a
  token, commits still happen locally and only the push is lost.
- **Do not run the container and a native instance against the same record repository.**
  A second instance refuses to start. Two mechanisms enforce it: an exclusive file lock
  (#44), and — because **Docker Desktop does not honour file locks on a bind mount**, which is
  exactly how `compose.yaml` gives the container your records — a liveness marker behind it
  (#52), verified on that mount. The refusal message says which one refused, because they
  recover differently. Two writers on one repository corrupt attempt numbering and fight over
  the git index, so run exactly one. Details and what is still unverified (Linux hosts,
  Windows, network filesystems):
  [`decisions/2026-08-06-record-repository-lock`](llm-wiki/wiki/decisions/2026-08-06-record-repository-lock.md).
- **The lock covers `.ps/` too, because that is inside the record repository.** Raw frames,
  per-problem timers and the backup marker live in `<your records>/.ps/`, beside the records
  they describe, so claiming the repository claims them with it. The server adds `.ps/` to
  your repository's `.gitignore` on startup if it is not already there — **a repository
  created before this change lists state files one at a time and ignores none of these**, and
  without the rule `git add --all` would commit your whole capture history a second time.
  Only the generated `/watch` token stays outside, next to the tool: it is a credential, and
  the record repository is pushed.
- **Times follow `TZ`, and unset it is UTC.** That is the clock your records are stamped with and
  the one the daily backup keeps, so a vault whose attempt history reads several hours off is
  this setting. The server prints the zone it resolved on every start. Set `TZ` in `.env`;
  `TRACKER_BACKUP_ZONE` exists only to push at a different hour than you record in.

---

## Where to go next

- [`README.md`](../README.md) — what this solves and how it differs from existing tools
- [`docs/programmers-protocol.md`](programmers-protocol.md) — the protocol, and the measured
  evidence behind every claim above
- [`docs/llm-wiki/index.md`](llm-wiki/index.md) — every decision and why it was made
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — issue-first, squash-only, and English for
  everything a contributor writes (the five user-facing pages also carry a Korean twin —
  [the decision](llm-wiki/wiki/decisions/2026-08-11-korean-for-the-user-facing-half.md))
