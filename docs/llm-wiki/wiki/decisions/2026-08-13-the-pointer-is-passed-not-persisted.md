---
type: decision
project: programmers-tracker
tags: [git, security, docker, bootstrap]
author: BrokenFinger98
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-13-the-map-becomes-a-workspace.md]
---

# The credential pointer is passed per command, never persisted in the user's repository

## Context

[[decisions/2026-08-13-the-server-prepares-the-repository]] moved pushes onto a GitHub token
stored at `<records>/.ps/git-credentials`, and pointed git at it the obvious way:

```kotlin
git("config", "credential.helper", "store --file=${file.toAbsolutePath()}")
```

That writes into the **record repository's own** `.git/config`, with an absolute path. Inside the
container that path is `/records/.ps/git-credentials`; the same `.git/config` is the user's
working copy on a host where `/records` has never existed.

Found by running it rather than reading it, on the first host-side push after the token landed:

```
$ git -C ~/Desktop/ps-records push --dry-run origin main
fatal: unable to get credential storage lock in 1000 ms: No such file or directory
To https://github.com/BrokenFinger98/ps-records.git
   6e0b3ff..5e58a35  main -> main
```

The push **succeeded** — the system's `osxkeychain` is consulted first and answers — and printed
`fatal:` anyway, because our `store` helper then tried to lock a file under a directory that is
not there. A status that disagrees with what actually happened is the defect
[[decisions/2026-08-11-a-watch-answer-is-not-a-promise]] was written about one level up, with the
sign flipped: there a green badge covered a lost submission, here a `fatal:` covers a push that
worked. Both teach the reader to stop believing the signal. And on a machine with no other helper
— Linux, or anyone without `osxkeychain` — the repo-local entry is the only one, supplies
nothing, and the host-side push genuinely has no credential.

## Options considered

1. **Leave it.** Rejected: it is only cosmetic on a machine that happens to have a second helper,
   and it is a real break on one that does not. The tool is distributed publicly; the developer's
   macOS is not the environment to design for (dev rules §9.1, the same rule `Asia/Seoul` broke
   until #243).
2. **Write the pointer into the container's own global config** (`/home/tracker/.gitconfig`).
   Keeps the shared `.git/config` clean and leaves a bare `git push` working inside the
   container. Rejected on the native path: `git config --global` in a `./gradlew bootRun` writes
   the *developer's* `~/.gitconfig` — the same class of trespass, one directory over, and worse
   for being invisible.
3. **Pass it per invocation** — `git -c credential.helper='store --file=…' push`. Nothing is
   persisted anywhere, so there is no host, container or native case to get right. Chosen.

## Decision

The credential *file* stays where #258 put it. The *pointer* is no longer written anywhere:
`CommandLineGitSync` prefixes every one of its own git invocations with `-c`, computed by a new
`PushCredential`, and `GithubRemote` writes no `credential.helper` at all.

Two properties the implementation must hold, both of which are the reason the fix is not a
one-line deletion:

- **The stale entry is removed on boot, with or without a token.** An install from before this
  carries the pointer, and nothing else will ever take it out. Values are read, filtered and
  rewritten rather than unset by a regex — a value-pattern would have to survive both path
  separators and the dot in `.ps`, and `store --file=~/.git-credentials` is git's *documented
  default*, so a looser filter deletes the user's own setting instead of ours.
- **The answer is the credential file's existence, never the token's.** `compose.yaml` promises
  *"You may delete this line after the first boot"*; gate the pointer on `GITHUB_TOKEN` and that
  promise fails the first time the container is recreated after the line is deleted.

## Rationale

The rule the old code broke is one this repository already had in writing and applied to the
records themselves: what belongs to the user is not ours to write into. `.gitignore` rules are
appended to a file that predates us (#130), a `.base` is never regenerated once touched (#254),
an existing `origin` is left alone (#258). `.git/config` is the same kind of object and was the
one place the token path forgot it.

Passing the pointer also removes a whole class of question rather than answering it. There is no
migration for a user who moves their records between machines, no interaction with a global
helper they already had, and nothing left behind if they delete the tool.

## Accepted costs

- **A bare `git push` inside the container no longer works** — `docker compose exec tracker git
  -C /records push` now answers `could not read Username`, because the credential comes from our
  argument list and not from any config the container reads. Measured: adding the same `-c` makes
  it work. The affordance was never documented and no maintained page relies on it, but it is a
  real loss for anyone debugging by hand, and the replacement is one flag longer.
- **`PushCredential.gitConfig()` stats the file on every git call.** Deliberately not cached:
  `GithubRemote` writes that file during the same boot that constructs `CommandLineGitSync`, and
  a cached "no" would leave the first boot after a token was added pushing without a credential.
  One `stat` against a process spawn is not a cost worth trading that for.
- **`push.default current` is still written into the repository's config** when a new origin is
  wired. It is a behavioural setting rather than a machine-specific path — it does not break
  anywhere — so it stays, and this is recorded so the next reader knows it was looked at rather
  than missed.

## Outcome

Implemented in #269 (issue #267). Verified live rather than only against the fake GitHub: after a
rebuild the boot log said `Removed our credential pointer from the record repository's config`,
`git config --local --get-all credential.helper` was empty, the host-side `push --dry-run`
reported `main -> main` with no `fatal:`, and the server's own command form
(`git -c credential.helper=… push --dry-run`) authenticated from inside the container.

The fake-GitHub tests cover the halves it can: no pointer written, a pre-existing one removed,
removal with no token, and a user's own helper left in place.
