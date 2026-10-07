---
type: decision
project: programmers-tracker
tags: [security, storage, mcp, git, links]
author: BrokenFinger98
created: 2026-10-07
updated: 2026-10-07
sources: [raw/sessions/2026-10-06-the-history-that-folded.md]
---

# No reader follows a link out of `problems/`

## Context

The records repository root holds, beside `problems/`, what must never leave the machine:
`.ps/git-credentials` — the push token, as `https://x-access-token:<token>@github.com` — the
`/watch` token and the raw frames. Git stores symbolic links, so a link under `problems/` can
arrive with a clone or a pull of the records repository, not only from someone with a shell on
the machine.

#353 bounded one reader, kept submit code, after three review rounds
([[decisions/2026-10-07-repair-steps-are-served-not-judged]], option 10). The same review
reproduced the exposure in a reader that had shipped long before: in an isolated copy, a
`statement.md` linked to `../../.ps/git-credentials` came back from `get_problem` as the
problem's `statement`, token included. That became #354, which named the readers that return file
content over MCP: `FileProblemStatements` and `FileGradingCodes`.

Before choosing anything, every reader in `src/main` that opens a file under `problems/` was
listed, with where its content goes (branch `fix/354-no-link-out-of-problems`):

| Reader | File | Where the content goes | Leaked through a link |
|---|---|---|---|
| `FileProblemStatements.of` | `statement.md` | `get_problem` over MCP | yes, reproduced |
| `ProblemReadme.statement` | `statement.md` | inlined into the problem's `README.md`, committed and pushed | yes; not in the issue |
| `CodeArtifacts.readAttempt` | the previous `attempts/NNN.<ext>` | `diffFromPrev` in `log/submissions.jsonl`, served over MCP and pushed | yes; not in the issue |
| `FileGradingCodes.runs` | `runs.jsonl` | run code over MCP | only from a target whose lines decode as the four-key run line |
| `FileDerivedArtifacts.examplesOf` | `examples.json` | example values written into a runner, committed and pushed | only from a JSON array of `{input, expected}` objects, since anything else fails to decode, the exception is dropped unlogged and the runner is refused; not in the issue |
| `FileGradingCodes.submitted` | `attempts/NNN.<ext>` | MCP | no, #353's bound |

The rest read nothing that leaves: `RunLog`'s idempotency check and last-byte check (a boolean and
a byte), `writeStatement`'s `exists`, `RecordLayout`'s directory listing (names), and the record
writer's `exists` before a scoped commit (git adds a link as a link). Nothing in `src/main` reads
`notes.md`, a problem page, a runner or an `NNN.raw.jsonl` back.

The issue looked at what MCP returns from a file. The three readers it did not name put what they
read into files that git publishes, and the worst of all six is one of them: `get_problem` serves a
statement to whoever holds the MCP token, while the README publishes it, in a repository that is
pushed after every pass.

## Options considered

1. **Copy #353's two private functions into each reader.** Five more copies of a check that took
   three rounds to get right, and the next reader is one more chance to get it wrong.
2. **Resolve links in `RecordLayout`.** Every writer names its files through it, and a writer
   does not want a refusal; `recordFile` is lexical on purpose, so it can be reasoned about
   without a filesystem.
3. **Bound only the two readers the issue named.** Leaves the README inline and the attempt diff,
   both of which leaked into what git publishes.
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

`adapter/store/ProblemFiles(layout)` offers `readAllBytes(candidate)` and `readString(candidate)`.
A candidate is resolved to its real path and read only when that path lies under the real
repository root with `problems` appended by name, and is a regular file; the real path is what
gets opened. Anything else, and any exception, answers `null`. `readString` is strict UTF-8 as
`Files.readString` is; `readAllBytes` leaves lenient decoding to the readers that did it before.
Only the root is resolved, because it may sit behind a link (macOS's `/var`, a `~/ps-records`
link); `problems` never is, because resolving a linked `problems` carries the bound to wherever
the link leads.

Every reader in the table goes through it:

- `FileGradingCodes`, submit code and the run log.
- `FileProblemStatements`, and `ProblemReadme` through it, so the page and `get_problem` show one
  text under one bound.
- `CodeArtifacts.readAttempt`. A refused previous attempt counts as a missing one, which already
  meant no diff rather than a diff against nothing.
- `FileDerivedArtifacts.examplesOf`. Refused examples are no examples, so the runner is refused.

`RecordLayout` stays lexical, and its `recordFile` documentation points at the helper as the
other half of the bound.

## Rationale

The bound #353 reached in three rounds — lexical, then real path, then not resolving `problems` —
now lives in one function with fourteen unit tests of its own, and a reader cannot forget the
regular-file check because it is never handed a path to forget it on.

Every reader-level test plants the link, reads the target through it, and only then asserts the
refusal, so each case is shown to be a real exposure first. Against the readers as they were
(measured on this branch before the change): the statement reader returned the token; the
README carried it under `## Problem`; the diff began `-https://x-access-token:…`; the run log
returned a line from outside; a runner was generated from examples outside. Then by mutation:
unbounding any one of the six readers fails its test, and removing any one of the helper's checks
fails a helper test — containment, the regular-file check (the FIFO test times out), resolving the
root only, element-wise rather than string containment, catching exceptions, resolving links at
all.

## Accepted costs

- **A deliberately linked `problems/`, problem directory or file yields nothing.** No statement,
  no code, no diff, no examples. The bound cannot tell a deliberate link from a planted one; this
  extends the cost #353 accepted for code to every reader.
- **An I/O error on a previous attempt no longer aborts the attachment.** `readAttempt` used to
  let `Files.readAllBytes` throw out of `writeCode`, which left the new submit's code unattached
  and `codePending`; the startup retry then fetched it again into the same exception, which
  nothing on that path catches (read from the code, not measured). It now counts as missing: the
  code is attached with no diff and a warning, and that diff is never computed later. Taken for
  the never-throws contract the other readers already keep.
- **A statement linked out of `problems/` is fetched again at every boot.** `StatementBackfill`
  reads it as missing, fetches it, and `writeStatement` finds the link's target present and
  writes nothing: one request per boot per such link, inside the per-boot cap and its pause. The
  same was already true of a `statement.md` that exists and cannot be read.
- **Only readers are bounded. Writers follow links.** Measured in a scratch copy on 2026-10-07:
  `Files.writeString` through a linked problem `README.md` overwrote `.ps/git-credentials`, a
  `runs.jsonl` append through a link appended to it, and a dangling `statement.md` link made the
  writer create a file outside `problems/`. An integrity exposure rather than a leak, and a design
  of its own (refuse, or replace the link), so it is not decided here.
- **The check and the open are two steps.** Someone with a shell can swap a path component in
  between. The threat answered here is a link that arrives with a clone, which does not move.
- **The root is resolved on every read.** One `toRealPath` per file; not cached, because the root
  may not exist yet when a reader is built.

## Outcome

#354 on `fix/354-no-link-out-of-problems`: the code and tests in `b70f242`, this page, the MCP
security note (`docs/mcp.md` and its twin) and progress in the commit after it. All gates green on
the branch: 1,881 tests (8 skipped, the C# execution suite without a .NET toolchain), ktlint, the
build, branch coverage (`adapter/store` 85%, floor 80) and the guards.

Not verified live. The bound changes nothing a normal records repository can see, so after a
rebuild `get_problem`, `repair_steps` and the problem pages should come out byte-identical.

Found in the audit and left for their own issues:

- **Writers follow links** — the accepted cost above.
- **A `.gitignore` that is a link ignores nothing.** Git does not read it: in a scratch repository
  on git 2.48.1, `git add --all` warned `unable to access '.gitignore': Too many levels of symbolic
  links` and staged `.ps/git-credentials`. Reconciliation is `git add --all`, `.ps/` is excluded by
  `.gitignore` alone, and `RecordRepositoryIgnores` reads and rewrites the rules through the link
  without noticing. A linked `.gitignore` arriving with a clone would have the next reconcile commit
  the push token, and the next pass push it. It sits at the root, outside `problems/`, so outside
  this decision — and it is the more serious of the two.
