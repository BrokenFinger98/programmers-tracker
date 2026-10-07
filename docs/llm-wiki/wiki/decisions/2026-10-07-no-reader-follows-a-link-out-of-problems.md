---
type: decision
project: programmers-tracker
tags: [security, storage, mcp, git, links]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-08
sources: [raw/sessions/2026-10-07-repairs-not-verdicts.md, raw/sessions/2026-10-07-the-readers-that-followed-links.md]
---

# No reader follows a link out of `problems/`

## Context

The records repository root holds, beside `problems/`, what must never leave the machine:
`.ps/git-credentials` — the push token, as `https://x-access-token:<token>@github.com` — and the
raw frames. (The `/watch` token and the session cookie are not there; they stay in the tool's own
`.ps/`, outside the records, per `compose.yaml:78-87`.) Git stores symbolic links, so a link under
`problems/` can arrive with a clone or a pull of the records repository, not only from someone
with a shell on the machine.

#353 bounded one reader, kept submit code, after three review rounds
([[decisions/2026-10-07-repair-steps-are-served-not-judged]], option 10). The same review
reproduced the exposure in a reader that had shipped long before: in an isolated copy, a
`statement.md` linked to `../../.ps/git-credentials` came back from `get_problem` as the
problem's `statement`, token included. That became #354, which named the readers that return file
content over MCP, `FileProblemStatements` and `FileGradingCodes`; its second comment, posted before
any code, added `CodeArtifacts.readAttempt`, whose diff lands in the pushed log.

Before choosing anything, every reader in `src/main` that opens a file under `problems/` was
listed, with where its content goes (branch `fix/354-no-link-out-of-problems`):

| Reader | File | Where the content goes | Leaked through a link |
|---|---|---|---|
| `FileProblemStatements.of` | `statement.md` | `get_problem` over MCP | yes, reproduced |
| `ProblemReadme.statement` | `statement.md` | inlined into the problem's `README.md`, committed and pushed | yes; not in the issue |
| `CodeArtifacts.readAttempt` | the previous `attempts/NNN.<ext>` | `diffFromPrev` in `log/submissions.jsonl`, served over MCP and pushed | yes; added by the issue's second comment |
| `FileGradingCodes.runs` | `runs.jsonl` | run code over MCP | only from a target whose lines decode as the four-key run line |
| `FileDerivedArtifacts.examplesOf` | `examples.json` | example values written into a runner, committed and pushed | only from a JSON array of `{input, expected}` objects, since anything else fails to decode, the exception is dropped unlogged and the runner is refused; not in the issue |
| `FileGradingCodes.submitted` | `attempts/NNN.<ext>` | MCP | no, #353's bound |

The rest read nothing that leaves: `RunLog`'s idempotency check and last-byte check (a boolean and
a byte), `writeStatement`'s `exists`, `RecordLayout`'s directory listing (names), and the record
writer's `exists` before a scoped commit (git adds a link as a link). Nothing in `src/main` reads
`notes.md`, a problem page, a runner or an `NNN.raw.jsonl` back.

The issue looked at what MCP returns from a file and, by its second comment, at the diff the log
carries. The two readers it did not name put what they read into files that git publishes, and the
worst of all six is one of them: `get_problem` serves a statement to whoever holds the MCP token,
while the README publishes it, in a repository that is pushed after every pass.

## Options considered

1. **Copy #353's two private functions into each reader.** Five more copies of a check that took
   three rounds to get right, and the next reader is one more chance to get it wrong.
2. **Resolve links in `RecordLayout`.** Every writer names its files through it, and a writer
   does not want a refusal; `recordFile` is lexical on purpose, so it can be reasoned about
   without a filesystem.
3. **Bound only the readers the issue named.** Leaves the README inline, which leaked into what git
   publishes, and the examples, which did from an examples-shaped target.
4. **Skip the run log and the examples, whose parsers already filter.** Each would then need its
   own argument about what its parser happens to drop, and the next format change could void it.
5. **One helper the readers open files through, offering reads rather than a checked path** —
   chosen. Handing out a checked `Path` would leave two things to every caller: the regular-file
   check, and reading the real path rather than the one it was handed.

Within option 5, how the bytes become text:

- (a) One `String` read with replacement decoding. The statement reader is strict today, so a
  `statement.md` re-saved in CP949 by a legacy editor would come back as replacement characters
  where it is absent now — a change nobody asked for, inside a security fix.
- (b) `readAllBytes` and `readString`, mirroring `Files` — chosen. Each reader keeps exactly the
  decoding it had.

## Decision

`adapter/store/ProblemFiles(layout)` — module-internal, since Kotlin has no package-private, and
used only by `adapter/store` — offers `readAllBytes(candidate)` and `readString(candidate)`. A
candidate is resolved to its real path and read only when that path lies under the real repository
root with `problems` appended by name, and is a regular file; the real path is what gets opened,
without following its last name. Anything else, and any exception, answers `null`. A missing file or
a dangling link (`NoSuchFileException`) is the normal path and says nothing. Every other refusal
logs one warning naming the candidate and the reason — `leads out of problems/`,
`not a regular file`, or the exception's simple class name — and never the content, an exception's
message or where a link leads, since each of those can name the file being kept out. `readString`
decodes with a new UTF-8 decoder, which reports malformed input rather than replacing it, so it is
exactly as strict as `Files.readString`; `readAllBytes` leaves lenient decoding to the readers that
did it before. Only the root is resolved, because it may sit behind a link (macOS's `/var`, a
`~/ps-records` link); `problems` never is, because resolving a linked `problems` carries the bound
to wherever the link leads.

Every reader in the table goes through it:

- `FileGradingCodes`, submit code and the run log.
- `FileProblemStatements`, and `ProblemReadme` through it, so the page and `get_problem` show one
  text under one bound.
- `CodeArtifacts.readAttempt`. A refused previous attempt counts as a missing one, which already
  meant no diff rather than a diff against nothing. Its warning says the code could not be read,
  not that none was stored.
- `FileDerivedArtifacts.examplesOf`. Refused examples are no examples, so the runner is refused, for
  a reason the adapter now gives itself: `no examples could be read`. Every generator calls an empty
  list `no examples were captured`, which a refused file was.

`RecordLayout` stays lexical, and its `recordFile` documentation points at the helper as the
other half of the bound.

## Rationale

**The bound is the publication boundary.** Git stores a link as a link. Measured on git 2.48.1 in a
scratch repository: a `problems/1-x/statement.md` linked to `../../.ps/secret` stages as mode
`120000` whose blob is the text `../../.ps/secret` — the path, not what lies behind it. So what lies
behind a link was never published by a push; it reached the remote only because a reader followed
the link and wrote what it found into the README, the log or a runner. The readers now publish what
git does — content under `problems/`, which git publishes as files — and nothing from behind a link
that leaves it. The bound sits just inside git's own: a link from `problems/` to a tracked file
elsewhere in the repository is refused, though git publishes that file in its own right.

The bound #353 reached in three rounds — lexical, then real path, then not resolving `problems` —
now lives in one class with seventeen unit tests of its own, and a reader cannot forget the
regular-file check because it is never handed a path to forget it on.

Every reader-level test plants the link, reads the target through it, and only then asserts the
refusal, so each case is shown to be a real exposure first. Against the readers as they were
(measured on this branch before the change): the statement reader returned the token; the
README carried it under `## Problem`; the diff began `-https://x-access-token:…`; the run log
returned a line from outside; a runner was generated from examples outside. Then by mutation:
unbounding any one of the six readers fails its test, and removing any one of the helper's checks
fails a helper test — containment, the regular-file check (the FIFO test times out), resolving the
root only, element-wise rather than string containment, catching exceptions, resolving links at
all. The review round's mutations are under Outcome.

## Accepted costs

The refusals among these fail safe: something legitimate reads as absent, and since the review round
each says so in a warning naming the path — with one exception: under a root configured as
`<link>/..`, `submitted` resolves a lexical path that does not exist and ends, silently, as a
missing file (measured). Two are reads the bound cannot stop — a directory swapped mid-read and a
hard link — accepted because each needs something acting on this machine at the time, and a shell
there can read the token directly.

- **A deliberately linked `problems/`, problem directory or file yields nothing.** No statement,
  no code, no diff, no examples, and a warning for each. The bound cannot tell a deliberate link
  from a planted one; this extends the cost #353 accepted for code to every reader.
- **An I/O error on a previous attempt no longer aborts the attachment, or the boot.** `readAttempt`
  used to let `Files.readAllBytes` throw out of `writeCode`. At capture time `ChannelCapture.attach`
  absorbs that and leaves the record `codePending`. At the next boot `attachPending` fetches the code
  and diffs again, and if the read still fails, nothing catches it: `StartupReconciliation.run()` calls
  `attachPending` unwrapped (`StartupReconciliation.kt:32`), and is itself called unwrapped from the
  `ApplicationRunner { runBlocking { startup.run() } }` in `CaptureConfiguration.kt:199`. Spring Boot
  fails startup on an exception from a runner (`callRunner` wraps it, `handleRunFailure` closes the
  context and rethrows out of `main`), and with `restart: unless-stopped` (`compose.yaml:18`) that is
  a crash loop for as long as the file stays unreadable and the fetch succeeds. Read from the code,
  not measured. It is now a warning and a missing diff. The cost: a transient read error at capture
  time used to be retried with its diff at the next boot; now the record is attached without it,
  and that diff is lost for good.
- **A refused `statement.md` is fetched again at every boot.** It reads as missing, so
  `StatementBackfill` fetches it again, inside its per-boot cap and pause. `writeStatement` then
  sees a file there — `Files.exists` follows a link — and writes nothing, and the pass still reports
  it filled until writers stop following links (#361). Read from the code, not measured. One request
  per boot per such file; the same was already true of a `statement.md` that exists and cannot be
  read.
- **Only readers are bounded. Writers follow links (#361).** Measured in a scratch copy on
  2026-10-07: `Files.writeString` through a linked problem `README.md` overwrote
  `.ps/git-credentials`, a `runs.jsonl` append through a link appended to it, and a dangling
  `statement.md` link made the writer create a file outside `problems/`. An integrity exposure rather
  than a leak, and a design of its own (refuse, or replace the link), so it is not decided here.
- **A directory swapped for a link between the check and the open is still followed.** The real
  path is opened without following its last name, so swapping the file itself fails;
  `NOFOLLOW_LINKS` covers nothing above it. Accepted: the server never pulls (it runs `init`,
  `remote`, `config`, `rev-parse`, `add`, `commit`, `status` and `push`, read from the code), so a
  swap needs a pull by hand landing inside that instant or someone with a shell, who can already
  read the token.
- **A hard link passes.** It is the file itself, so a hard link to the token under `problems/` is
  read (measured); git cannot store or deliver one, so making it takes a shell here, which can read
  the token directly.
- **A hand-made case variant such as `Problems` is refused on macOS.** The writers' `problems/...`
  lands in `Problems/` on a case-insensitive volume, `toRealPath` returns the case on disk, and
  `startsWith` compares case-sensitively, so every read is out of bounds (measured on macOS 27).
- **A records root configured as `<link>/..` is refused.** `RecordLayout.repositoryRoot()`
  normalizes lexically, so a root `a/link/..`, with `link` pointing at `x/y`, is bounded at
  `a/problems`, which does not exist, while the writers' files land in `x/problems` (measured).
  Pre-existing since #353 for `submitted`; now every reader.
- **The root is resolved on every read.** One `toRealPath` per file; not cached, because the root
  may not exist yet when a reader is built.

## Outcome

#354 on `fix/354-no-link-out-of-problems`: the code and tests in `b70f242`, this page, the MCP
security note (`docs/mcp.md` and its twin) and progress in the commit after it. All gates were
green on the branch then: 1,881 tests (8 skipped, the C# execution suite without a .NET
toolchain), ktlint, the build, branch coverage (`adapter/store` 85%, floor 80) and the guards.

**Review round.** Two reviews approved the change with fixes, one marked Important: refusals were
silent. Three commits answer them. `b6048fb` — every refusal but a missing file or a dangling link
warns, and the two log lines that called a refusal an absence are reworded. `edd068b` — the checked
real path is opened without following its last name, `readString` decodes through a reporting
decoder (identical to `Files.readString` on ten edge inputs, from overlong and surrogate bytes to a
BOM, on Temurin 25), and `ProblemFiles` is internal. `a2cbb87` — `FileGradingCodesTest` keeps one
link-out test per reader method, and each case of the bound is pinned once, in `ProblemFilesTest`.
By mutation: each warning, the `NoSuchFileException` exemption, naming the class rather than the
message, strict decoding and opening the real path rather than the candidate fail a test; after the
reduction, reverting any of the six readers to a direct `Files.read*` still does. Dropping
`NOFOLLOW_LINKS` survives, as it can only matter inside the race. Gates green: 1,879 tests (8
skipped), ktlint, the build, branch coverage (`adapter/store` 85%, 408 of 480) and the guards.

**Re-review.** One Important: the test log capture read only each event's formatted message, so a
throwable passed as the last argument escaped every "never the message" assertion — the
re-reviewer's mutant, `failed()` logging `cause` too, passed all of `ProblemFilesTest` while the
written log carried the link target's path. `d04ec97` captures what a layout writes, message then
throwable text, and refuses to listen at a level the logger is not enabled for; the same mutant now
fails. `af55d58` words the statement backfill's work list as problems "with none readable" rather
than "recorded before it was kept", which a refused file was not. This page, the `ProblemFiles` and
`RecordLayout.recordFile` KDocs and the MCP note no longer place the `/watch` token in the records
repository. Gates green again: 1,879 tests (8 skipped), ktlint, the build, branch coverage and the
guards.

⚠️ (superseded the same night by the live verification below) Not verified live. The bound changes
nothing a normal records repository can see, so after a rebuild `get_problem`, `repair_steps` and
the problem pages should come out byte-identical, and a normal boot should log no
`Treating ... as absent` line.

**Verified live 2026-10-07 at 23:03 KST.** PR #363 was squash-merged as main `a3838c0` and the
container rebuilt (raw/sessions/2026-10-07-the-readers-that-followed-links.md).

- **CI.** All seven checks passed. On Windows this was the first time the code that opens the real
  path with `NOFOLLOW_LINKS` ran.
- **Boot.** The container was healthy in about 6 s. The boot logged no `Treating … as absent` line
  and no WARN or ERROR, and startup code attachment reported `attached=0`.
- **Every MCP read, before and after.** Each read the server offers was saved twice: before the
  merge (22:57) and after the deploy (23:03). Each answer's `structuredContent` was stored with
  sorted keys. The reads:
  - `submissions`;
  - `stats` grouped by verdict, language, problem, part and level;
  - `list_problems(status=attempted)`;
  - `review_queue`;
  - `slow_passes`;
  - `repair_steps(limit=1000)`, total 65;
  - `get_problem(include=["code","runs"])` for all 25 lessons on record.

  The 36 files, 35 answers plus the lesson list, were identical.
- **The record repository.** It stayed at HEAD 7e144fa with a clean status, so the boot changed
  nothing the server writes there. No grading arrived between the snapshots, so no problem page was
  regenerated. The README half of the prediction therefore rests on the tests and on that clean
  repository.

Found in the audit and left for their own issues:

- **Writers follow links (#361)** — the accepted cost above.
- **A `.gitignore` that is a link ignores nothing (#360).** Git does not read it: in a scratch
  repository on git 2.48.1, `git add --all` warned
  `unable to access '.gitignore': Too many levels of symbolic links` and staged
  `.ps/git-credentials`. Reconciliation is `git add --all`, `.ps/` is excluded by `.gitignore`
  alone, and `RecordRepositoryIgnores` reads and rewrites the rules through the link without
  noticing. A linked `.gitignore` arriving with a clone would have the next reconcile commit the
  push token, and the next pass push it. It sits at the root, outside `problems/`, so outside this
  decision — and it is the more serious of the two.
