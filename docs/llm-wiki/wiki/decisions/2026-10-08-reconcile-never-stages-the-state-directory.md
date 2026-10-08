---
type: decision
project: programmers-tracker
tags: [security, git, credentials, links, storage, push]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-08
sources: [raw/sessions/2026-10-07-the-readers-that-followed-links.md]
---

# Reconciliation never stages the state directory, and the tracker searches what it commits and pushes for tokens

## Context

The records repository root holds `.ps/`, the tracker's own state
([[decisions/2026-08-10-state-beside-the-records]]): the push token at `.ps/git-credentials`, as
`https://x-access-token:<token>@github.com`, the raw frames, the timers and the backup marker.
Reconciliation runs at every boot, after every pass and in the daily backup, which pushes straight
after it. It was `git status --porcelain`, then `git add --all`, then `git commit`, and one
`.gitignore` rule, `.ps/`, added at boot by `RecordRepositoryIgnores`, was all that kept the state
out. `push()` checked nothing.

**The issue.** The #354 audit found that the rule can be switched off from outside
(raw/sessions/2026-10-07-the-readers-that-followed-links.md). With `.gitignore` a symbolic link, git
read no rules and `git add --all` staged `.ps/git-credentials` (git 2.48.1). Git stores links (mode
`120000`, measured in #354), so such a `.gitignore` can arrive with a clone or a pull, and the next
pass would push the token.

**The first fix, and what measuring it found.** Git follows no `.gitignore` link at all: one linked to a
regular file that holds `.ps/` gives the same warning, because git opens a directory's `.gitignore`
with `PATTERN_NOFOLLOW` (`dir.c`, read at v2.48.1). `RecordRepositoryIgnores` read and wrote through
the link, so it appended the rules to a file outside the repository, or created the file a dangling
link named. Git's warning reached the dirtiness check, which read both streams, so every
reconciliation became an empty commit that failed. A `.gitignore` that was not UTF-8 was read as
empty and overwritten. The first was measured in a scratch repository; each of the others is a test,
red against the old code.

**The adversarial review of that fix.** It reproduced five more routes, in the same clone-or-pull
threat model, for the token to reach a commit or a push. Each ran with the real classes against a
bare remote and fake tokens, and all five leak on main too:

- **F1 `.pſ`** (`.p` and U+017F, which case-folds to `s`). APFS answers the server's `.ps` with a
  `.pſ` a clone delivered. Git sees the name on disk, which the ignore rule, the pathspec and the
  ignore probe all miss — with a healthy `.gitignore`, and silently.
- **F2, a tracked link at `.ps/git-credentials`.** A pull replaces the ignored file with it, and
  `storeCredential` wrote the token through it into a tracked file. `PushCredential.gitConfig()`
  checked the store with `Files.exists`, which follows the link.
- **F3, `.ps` itself a tracked link into the tree.** Git treats an ignored file as expendable: a
  plain `git pull` deleted the ignored `.ps/`, credential and raw frames included, and checked out the
  link in its place (measured on 2.48.1 and on 2.53.0 in the container). Every state write then
  lands in a tracked path.
- **F4 `.PS`** on a case-insensitive volume. The case-sensitive pathspec missed it, and the first fix's
  warning then claimed reconciliation "leaves .ps/ out regardless".
- **F5.** `push()` sent a commit another tool had made — an editor plugin's `add -A` under a broken
  `.gitignore` — exactly as it was.

It also found smaller gaps:

- **F6.** `status.showUntrackedFiles=no` made every new record read as nothing to reconcile.
- **F7.** During a merge, reconciliation's `add --all` marked every conflict resolved, and its partial
  commit then failed at every backup tick.
- **F8.** The first fix reads the dirtiness check's stdout alone. An untracked directory git cannot
  open is reported only on stderr (`could not open directory`), so its records are skipped without a
  word, where main failed loudly.
- **F9.** The first fix's permission-keeping replace kept a widened state file widened.
- **F10.** A read-only `.gitignore` was rewritten anyway: a replace needs only the directory.
- **F11.** A submit could stage a path under `.ps`.
- **F12.** `[.]ps/**` leaves out what is under `.ps`, not the entry: a `.ps` that is a file or a link
  was committed itself (its target path, not the state).
- **F13** (inferred, not reproduced). A FIFO swapped in between the store's regular-file check and its
  no-follow open would block the open, and boot with it. It needs a local race.

The lesson of the review: every guard was a path, and every path had an alias.

**The second round of reviews, of `7ece5f7`.** A quality review found nothing Critical or Important.
Its seven Minors were: overclaims in the docs and the code (M1); other remotes masking the push range
(M2); the first push's cost (M3); a listing check no CI exercised, because macOS's `toRealPath` masked
its mutant and Linux cannot fold names (M4); a refused commit leaving the token's file staged, since
`add` ran before the search (M5); a misleading WARN on an unborn branch (M6); and two nits (M7). It
also found that the first draft of this page called `adapter/git → adapter/store` the first
dependency between adapters, which it is not.

The adversarial review found F1–F4 closed on the host and in the image, and new routes. Each was
reproduced with the real classes, a bare remote and fake tokens:

- **N2 (High), a tracked `.ps/git-credentials`.** A pull delivers it, as a file or a link. The identity
  check looked at `.ps` alone, so the server renamed the real token onto a tracked path: `git status`
  showed `M .ps/git-credentials`, and any other tool's `commit -a` and push would publish it.
  **N2a:** one character in that tracked file became a stored "token" that every file matched, so
  every commit was refused with a false alarm to rotate it.
- **N1, a tracked link inside `.ps`** (`.ps/raw` → `../problems/zz`). Raw frames written through it
  reached the server's own commit and push.
- **N7, a tracked link at `.ps/seeds.json`.** `SeedLedger` wrote through it with `Files.writeString`,
  overwriting a file outside `.ps` at every boot.
- **N3b.** State written while `.ps` was an alias was committed once the alias was removed.
- **N3.** Following the WARN's advice — take a tracked `.ps` link out of the index, put a real directory
  in its place — made every partial commit fail: `'.ps' does not have a commit checked out`.
- **N4 and N5, the push.** `--not --remotes` let another remote's refs, or stale ones, mask the range.
  A bare `git push` obeyed `remote.origin.push` or `push.default=matching`, sending branches the
  search never looked at.
- **R1.** Once the advice to rotate was followed, the old token was no longer stored, and passed the
  gate in history.
- **N6.** Commit and tag messages are not searched.
- **Replace refs.** The gate read a replacement object while the push sent the original.
- **N11.** The submit path had no merge wait.
- **N10.** Every check is of a path at one moment, and the write resolves it again. With no store, the
  gate also had nothing to search for.

The second lesson: `.ps` is what is inside it as well as what it is, and a gate that searches only for
the stored value goes blind the moment the store changes.

**The third round of reviews, of `ea1357c`.** Both blocked the merge, on one finding each:

- **U1 (High, adversarial).** On APFS — the host, and Docker's virtiofs over a macOS bind mount — a
  pulled `.pſ/x` lands inside the real `.ps`, while the index records `.pſ/x`. The name on disk stays
  `.ps`, so the listing check passed, and the tracked question, `ls-files ':(glob,icase)[.]ps/**'`,
  folds ASCII alone and found nothing. With real pulls and the round-3 classes, on the host and in the
  image, the server committed and pushed its own timers and orphaned frames under `.pſ/`, and renamed
  the real token onto a pulled, tracked `.pſ/git-credentials`.
- **A regression on a healthy repository (Critical, quality).** The walk for links below `.ps` used
  `Files.walk`, which throws when an entry vanishes between being listed and being read, and that read
  as a refusal. The tracker's own temp-and-rename writes make entries vanish all the time: 444 of 3,000
  inspections of a healthy `.ps` refused (434 here), and 3 of 500 sessions (2 here) lost every raw
  frame, because the raw log kept its first frame's verdict for the session. Skipping a refused
  writer's frames, round 3's choice, was itself a discard of originals, which the constitution forbids.

Three Importants: a repository with no remote — a documented way to run — searched all of history at
every push, 23.4 s for 1,660 commits every minute the backup was due; the gate's fail-closed branches
had no tests, and their mutants survived; and the frames. The adversarial review's ENV: with
`GIT_LITERAL_PATHSPECS=1` in the server's environment, the tracked question answered "nothing". The
quality review's Minors: an 11-line method, a cast where an exhaustive `when` belonged, asymmetric
names, a fail-open default (`TrackedState.UNASKED`), a state read that followed links, a second
spelling of the `.ps` pathspec, and three sentences that were not true.

Writing the tests for those branches found one more: `git grep` over a commit whose blob cannot be
read says `unable to read` and exits 1 — the code for "nothing found" — on 2.48.1 here and on 2.53.0
in the image. The gate took it for a clean search.

The third lesson: a check is as good as the question it asks. ASCII folding answered a Unicode
question, a walk answered a question no writer had, and an exit code answered for a search that had
not read everything.

## Options considered

1. **Trust `.gitignore`, and refuse to reconcile when `git check-ignore` says `.ps/` is not ignored.**
   The issue's first direction. It fails closed, but it stops every backup until someone fixes the
   file. It also leaves the token's safety in a file that arrives with a clone.
2. **Exclude `.ps/` from reconciliation by pathspec.** Kept, as one layer. Alone, it is a path guard,
   and F1, F3 and F4 are aliases of the path, while F5 is not a reconciliation at all.
3. **Stage an allowlist of record paths.** It would miss the owner's own files — notes, the vault
   settings, the dashboard — which reconciliation exists to back up
   ([[decisions/2026-08-13-the-vault-is-not-only-records]]). It is also a list that must grow with every
   new kind of file, the failure `RecordRepositoryIgnores` documents for #122.
4. **Search the content for the token itself, before every commit and every push.** Chosen as the
   last line. It holds wherever the token turns up in what is sent, so it is the one guard no path
   alias defeats. It cannot stand alone, though. Searching only for what the store held, it had
   nothing to search for with no credential stored, and missed a token rotated out of the store (R1).
   F3's raw frames would still be committed. And a refusal stops every commit, where an exclusion
   lets the records through.

The details each layer needed, as measured — several depart from the brief they were built from:

- **The pathspec's spelling.** `:(exclude).ps` made `git add --all` exit 1 in every healthy
  repository: git reports an ignored path that an argument names, and an exclusion counts as naming it
  (`builtin/add.c`; `exclude_matches_pathspec` in `dir.c` compares the text before the first wildcard
  and does not skip exclusions). Every spelling that starts with `.ps` failed. `[.]ps` leaves no text
  before the wildcard. `icase` was added for F4. `:(exclude)*.ps` also passes, and drops every name
  ending in `.ps`, `notes/figure.ps` included (measured). The entry needs its own exclusion beside
  `[.]ps/**` (N3, F12): a partial commit takes every path the pathspec matches in HEAD as well, found
  a directory where a link was tracked, and stopped (exit 128 without it, 0 with it, on 2.48.1 and
  2.53.0).
- **The state directory's identity.** The brief asked for `toRealPath` compared by name. On the
  macOS host (JDK 25.0.3) it returns the name on disk. In the tracker's own image on a macOS bind
  mount, where git sets `core.ignorecase=true`, the real path of `.PS` and of `.pſ` came back as `.ps`.
  `readdir` returns the name on disk in both, so the root's own listing decides. The listing is handed
  in, so one test pins that check on any filesystem: with the check removed, it fails on macOS too.
- **What a push sends.** The brief named `@{u}..HEAD`. The tracker sets `push.default=current` and no
  upstream, and `@{u}` failed (exit 128) on 2.48.1 and 2.53.0. Every push would then have searched
  all of history. `git rev-list HEAD --not --remotes` names what no remote-tracking branch holds, and
  a push updates the branch it pushed: three commits before the first push, none after. Round 3
  narrowed it to the remote pushed to (N4): with a second remote holding a commit, `--remotes` listed
  0 commits and `--remotes=origin` 1.
- **The ignore probe.** `git check-ignore -q .ps` reported a working rule as broken twice: before
  `.ps` exists, and with a state file staged by hand. `--no-index .ps/` asks the rules.
- **GitHub's token shapes.** The brief gave `gh[pousr]_[A-Za-z0-9]{36,}`. Classic tokens are 30 random
  characters and a 6-character checksum after the prefix, 36 in all (GitHub's own account of the
  format), so `{36,}` stands. GitHub also documents a stateless installation token,
  `ghs_<app id>_<JWT>`, with an underscore after the prefix, so the class gained `_`. The fine-grained
  token's length is not documented. The review's pattern assumed 82 characters, and `{60,}` keeps a
  floor below that rather than an exact length nobody promised.

**Whether the other state writers refuse too.** Round 2 said no: raw frames, timers, the backup marker
and the seed ledger kept writing into whatever answered to `.ps`, because a capture that cannot be
replayed should not be dropped over where it lands, and the tracker would not commit it while git
refused. The second review showed that reasoning to be wrong in three ways. The tracker did commit it,
once the alias was gone (N3b). A link inside `.ps` passed the check altogether (N1). And a write
through a link is not a write into `.ps` at all — the ledger overwrote a file outside it (N7). Round 3
reverses it.

**Where `StateDirectory` lives**, now that `adapter/git` needs it. The quality review weighed four
places. Moving it into `adapter/git` would put the store's own layout in another adapter, and make a
cycle the moment a store class used it. A port in `application` would carry a filesystem concept into
that layer, as an interface with one implementation that no test needs — the speculative interface
[[decisions/2026-08-05-hexagonal-architecture]] rules out. A
package shared by both adapters is the cleanest end state, but it takes `AtomicStateFile` along and
touches four unrelated store classes: a refactor of its own. So it stays in `adapter/store`, the one
edge goes one way, and the rule is written down (dev rules §1).

**When the writers ask.** At every write, or once. A walk of `.ps` holding 5,000 entries took
14–17 ms and the `ls-files` question about 6 ms (measured), and a grading sends a frame per testcase.
The state files ask at every write — a problem opened, a sensor report, a backup, a seed — at about
20 ms a question with 5,000 entries in `.ps`. The raw log asks once per session, at its first frame,
and again before setting a session aside or keeping an orphan.

**When git cannot say what it tracks.** Commits, pushes and the credential refuse: unknown is not
clean. The state writers go ahead. Their refusal answers a link, an alias or a tracked file that is
known to be there. An unknown is far more often a git that is missing or busy, and dropping captures
over it would lose data for nothing that has been seen.

Round 4 weighed four more:

**What git tracks, asked how.** A pathspec cannot spell every fold — `icase` is ASCII — so every index
entry is listed and judged by its first segment: equal to `.ps` ignoring case as Java folds it
(`".pſ".equalsIgnoreCase(".ps")` is true), or resolved by the filesystem to the same directory as `.ps`
(`Files.isSameFile`), which catches a fold no case rule knows, a short name, a link. Listing 5,000
entries took about 9 ms (measured), against about 6 ms for the pathspec question it replaces.

**The walk: made to tolerate a vanishing entry, or gone.** The quality review's sketch tolerated one
and measured 0 of 3,000 and 0 of 500. Kept, the walk would still read every entry below `.ps` at every
state write and at every session's first frame, to say whether a link stood anywhere — and no writer
needs that. A link a pull delivers is tracked, and the tracked question refuses it under any name. A
link left untracked, after the owner's own `git rm --cached`, misleads only a writer whose path runs
through it. So the walk is gone, and each writer checks its own path before every write: `.ps` and each
directory below it, none of them a link, a stat apiece. State files are renamed into `.ps`, which
replaces a link rather than writing through it, and a state read never follows one. Nothing reads what
another writer is replacing, so the tracker's own writes cannot refuse it. "When the writers ask"
above is superseded: the tracked question runs at every state write and at a session's first frame,
and a stat per directory at every frame.

**Where refused frames go.** Memory, or a quarantine directory outside the records repository. A
quarantine survives a crash, but it is another configured path to ship — the Docker image mounts the
records and nothing else, and no path is hard-coded. Memory needs nothing new and is bounded: a
submit's frames go whole to its attempt file, which lies outside `.ps`, as soon as the grading is
recorded; runs and orphans wait until `.ps` is usable again. Memory, at most 8,000,000 characters
across the log.

**A push with nowhere to go, and one that keeps failing.** A remote with neither `url` nor `pushurl`
is nowhere to push, so nothing is searched for it — `pushurl` beside the `url` the brief named,
because a push needs only that. A head already searched clean is remembered with its remote and a
SHA-256 digest of what was stored, so a push that keeps failing is not searched again; a new head,
remote or stored token is. In memory only: a restart searches once more.

## Decision

Six layers, each covering what the one before cannot:

1. **The pathspec.** Reconciliation checks, stages and commits with
   `. :(exclude,glob,icase)[.]ps :(exclude,glob,icase)[.]ps/**`, the entry and what is under it,
   whatever `.gitignore` says. On an unborn branch it is still a root commit. The submit path stages
   only the record's paths, and drops any whose first segment is `.ps` in any case.
2. **The state directory, what it is and what git tracks of it** (`StateDirectory`, `adapter/store`).
   Absent, `.ps` is created as a real directory. Present, it must be:
   - listed in the root by exactly that name;
   - a directory without following a link;
   - at the real root plus `.ps` as its real path;
   - free of anything git tracks that is it or under it: every entry `git ls-files -z` lists is judged
     by its first segment — `.ps` ignoring case as Java folds it, or the same directory as `.ps` on disk.
     The question is the `TrackedState` port, declared in `adapter/store` and answered by `adapter/git`'s
     `TrackedStateEntries`. It has no default: a writer never assumes "nothing tracked".

   Nothing walks the directory. `forGit()` refuses when any of these fails, or when git cannot say:
   `CommandLineGitSync` runs no commit, reconciliation or push, and `GithubRemote` stores no credential
   and wires nothing. Git is still asked what it tracks, and where a push would go. A refusal carries a
   typed `Refusal`: `NOT_THE_DIRECTORY`, `TRACKED` and `HOLDS_A_LINK` stand until the repository
   changes; `UNANSWERED` and `NOT_INSPECTED` are transient. One WARN names the reason, never content,
   and the way out: `git rm -r --cached .ps`, and commit that.
3. **The state writers.** The credential store, the raw frames, the timers, the backup marker and the
   seed ledger ask `forWriting()` — `forGit()`, except that git which cannot say does not stop them —
   and never write through a link. Each state file is a temporary file renamed over the old one inside
   `.ps`, which replaces a link rather than writing through it, and starts owner-only; a state read
   never follows a link, and a link where a document should be reads as none. `gitConfig()` offers the
   helper only for a regular file. The raw log checks its own path before every frame — `.ps`, `raw`,
   `recorded`, `orphans`, none of them a link, a stat apiece — and asks `forWriting()` at a session's
   first frame, and again at the next while the refusal is transient. While the directory is refused,
   a state file's write is skipped and **raw frames are held in memory**, at most 8,000,000 characters
   across the log: a submit's go whole to its attempt file, a run set aside and an orphan are written
   once `.ps` is usable again, and what is still held at shutdown is written if it can be and said if
   not. Each reason is said once per writer. `.ps` is spelled once, `StateDirectory.NAME`; the writers,
   the ignore rule, the credential path and the pathspec derive from it, and one test pins that they
   agree.
4. **The content gate**, in `CommandLineGitSync`:
   - Before staging, `git grep --untracked` searches the working tree under the commit's pathspec, so a
     refused commit leaves nothing staged. After staging, `git grep --cached` searches the index.
   - Before every push, each commit the push would send is searched, 256 to a search.
   - Each search looks for two things. First, GitHub's token shapes, `gh[pousr]_[A-Za-z0-9_]{36,}`
     and `github_pat_[A-Za-z0-9_]{60,}`, passed in argv because they are not secret. Then the stored
     value, read from `.ps/git-credentials` without following a link: each stored line, and the secret
     in it raw and percent-decoded, as fixed strings on stdin, never in argv. A prefix of the token, or
     the user name every token shares, is never a pattern.
   - Refused: a store that is not a regular file or cannot be read (a FIFO is never opened), a match,
     a search that fails — or that exits as if it found nothing after an `error:`, such as a blob it
     could not read — and outgoing commits that cannot be listed. Each refusal is one WARN that names
     why and never the token, and a false. A match says to revoke the token on GitHub and remove it
     from history.
   - A head already searched clean, for the same remote and the same stored token, is not searched
     again by the same server.
5. **The push.** `git push <remote> HEAD:refs/heads/<branch>`: the current branch alone, named on the
   command line, so `remote.<r>.push` and `push.default` send nothing else. The remote is
   `branch.<b>.pushRemote`, else `remote.pushDefault`, else `branch.<b>.remote`, else `origin`; the
   branch goes to its own name there, and `branch.<b>.merge` is not consulted. A remote with no `url`
   and no `pushurl` is nowhere to push: nothing is searched, and the WARN says so. The commits searched
   are `rev-list HEAD --not --remotes=<remote>`. An unborn HEAD is nothing to push, says nothing and
   answers true — which the daily backup records as a success (#372). A detached one is not pushed,
   and says so.
6. **Git's environment** (`GitProcess`). Every git call of the tracker runs through one helper, with
   `GIT_TERMINAL_PROMPT=0` and `GIT_NO_REPLACE_OBJECTS=1` — so the search reads the objects a push
   sends — and without `GIT_DIR`, `GIT_WORK_TREE`, `GIT_COMMON_DIR`, `GIT_INDEX_FILE` and the four
   pathspec switches, whatever the server was started with.

Smaller items:

- The dirtiness check reads stdout alone and lists untracked files with `--untracked-files=all`.
- A merge, cherry-pick, revert or rebase in progress, found through `git rev-parse --git-path`, makes
  reconciliation and the submit path wait: one WARN per instance, false, nothing staged.
- `AtomicStateFile` leaves state files owner-only by default. Only `RecordRepositoryIgnores` asks it to
  keep the `.gitignore`'s permissions, best effort.
- `RecordRepositoryIgnores` never reads or writes a `.gitignore` that is not a regular file, and it
  leaves a read-only one alone. Each is said in a WARN.
- When git does not ignore `.ps/`, one WARN per instance says so and names the likely cause.

## Rationale

**The content gate is the last line because it is the only layer that is not about a path.** Every
route the first review found turned a path guard against itself. The search does not care where the
token sits in what is sent — under `.pſ`, through a link, in a commit someone else made. The second
review found the limits of the other kind: a gate that knows only the stored value. The token shapes
make it independent of the store. Each vector, and what stops it now:

| Vector | Stopped by | Test, red against the code before it |
|---|---|---|
| F1 `.pſ` | identity (listing); the content gate on any filesystem | `a state directory the filesystem folds from a long s refuses every commit`; `a token in any tracked path is never committed` |
| F2 link at the store | tracked: refused; untracked: the writer replaces it; the helper skips one | `stores nothing when the credential path is a tracked link`; `replaces a link at the credential path rather than writing through it`; `points git at nothing when the store is a link`; `a credential store that is not a regular file refuses every commit and push` |
| F3 `.ps` a link | identity | `a state directory that is a link into the tree refuses every commit and push`; `stores no credential into a state directory that is a link` |
| F4 `.PS` | identity on a case-insensitive volume; `icase` where both can exist | `a state directory the filesystem folds from another case refuses every commit`; `the state directory is left out in any case` |
| F5 another tool's commit | the push gate | `a commit another tool made with the push token is never pushed` |
| N2 a tracked store | tracked entries | `a credential store a pull delivered stops every commit and push for what it is`; `stores no token over a store git tracks` |
| N2a a one-letter store | the same refusal, for what it is, before any search | `a credential store a pull delivered stops every commit and push for what it is` |
| N1 a link inside `.ps` | tracked: refused; the raw log checks its own path | `a link inside the state directory stops every commit and push`; `no frame is written through a link where the raw directory was` |
| N7 a link at the ledger | tracked: refused; untracked: replaced, never written through | `the ledger never writes through a link` |
| N3b state piled up under an alias | the writers ask first | `nothing piles up where the state directory pointed while it was a link`; `a state file is not written while the state directory is not its own` |
| N3 the advice breaks commits | the entry excluded | `a state directory that replaced a tracked link does not stop reconciliation` |
| N4 / M2 another remote masks the range | `--remotes=<remote>` | `a commit another remote holds is still searched before it is pushed here` |
| N5 push settings send more | the explicit refspec | `a push sends the current branch alone, whatever the push settings say` |
| R1 a token no longer stored | the token shapes | `a GitHub token no longer stored is still found before a push`; `a classic GitHub token is refused with nothing stored`; `a fine-grained GitHub token is refused with nothing stored` |
| M5 a refusal leaves it staged | the search before staging | `a refused commit leaves nothing staged` |
| M6 an unborn branch | nothing to push | `a branch with no commit yet has nothing to push, and says nothing` |
| N11 a submit during a merge | the wait | `a submit waits out a merge in progress and leaves it untouched` |
| Replace refs | `GIT_NO_REPLACE_OBJECTS=1` | `a replace ref does not hide a commit from the search` |
| M4 the listing check | the injected listing | `a directory the root does not list under exactly that name is not the state directory` |
| U1 a tracked `.pſ`, state | every index entry, judged in any fold | `state a fold of the name tracks is never written, committed or pushed`; `an entry under a Unicode case fold of the name is answered yes` |
| U1 a tracked `.pſ`, the token | the same | `stores no token where a fold of the name is tracked` |
| A fold no case rule knows | the same directory on disk | `an entry the filesystem resolves to the state directory is answered yes` |
| ENV, the server's environment | `GitProcess` | four `GitProcessTest` cases; `a tracked state file is found whatever index the environment names` |
| The walk racing the tracker's own writes | no walk | `a healthy directory is never refused while the tracker's own writers replace their files` |
| A passing refusal kept for a session | asked again | `a session refused for a moment keeps every frame, in order` |
| A link swapped in between two frames | a stat per directory, per frame | `a raw directory swapped for a link mid-session is never written through` |
| Frames skipped while refused | held in memory | `a submit refused for good still reaches its attempt file whole`; `a run set aside while refused is written once the state directory is usable again`; `orphans kept while refused are written once the state directory is usable again`; `held frames stay within their limit, and going over it is said`; `what is still held when the log closes is said`; `what is held is written when the log closes, once the state directory is usable` |
| A state read through a link | no-follow | `a link where the document should be reads as none` |
| One WARN used up by a passing refusal | once per reason | `each reason a write is refused for is said` |
| "Stayed in the raw directory" | said as it is | `a grading whose frames were never kept says so` |
| An unreadable blob read as clean | an `error:` is not "nothing found" | `a blob a push would send that cannot be read stops the push` |
| No remote, all of history searched | nothing searched | `a repository with no remote fails its push without searching` |
| The same head searched at every attempt | remembered | `a head already searched clean is not searched again`; `a new head is searched again` |

The new unit tests for `StateDirectory`, `TrackedStateEntries` and the writers' guard were red as
compile errors first, since the API they call did not exist. In round 4 the store's implementation was
written before its tests, so their red was taken afterwards and against the code before the change: a
scratch worktree at `0590dac` ran each behaviour through the old API — 434 of 3,000 inspections
refused, 2 of 500 sessions lost, every frame lost after a passing refusal, a refused submit's
`complete()` throwing `NoSuchFileException`, a run and the first orphan never written, a frame written
through a link swapped in mid-session, a read returning the link's target, one WARN for two reasons.
The fail-closed pins — a missing tree, a ref naming no object, a clean filter — held already, and
their mutants die. Three tests pin what held already and
were green before: `strings that only resemble a token are not refused`,
`every state writer, the ignore rule and the pathspec agree on one directory` and
`a detached head is not pushed`. Each of the first two dies under a mutant: the token class widened to
`gh[a-z]_`, or its length cut to `{4,}`; `AtomicStateFile.under` or the ignore rule naming another
directory.

**Each layer is checked on its own.** Round 2's mutants: with the content gate removed, three tests
fail for the commit half and two for the push half. With the identity check removed, three
`CommandLineGitSync` and two `GithubRemote` tests fail. A writer that follows links fails two tests,
and a helper offered for a link fails one. Round 3's, each against the final code:

- the entry exclusion removed: 1 test;
- the walk for links removed: 3;
- git answering that nothing is tracked: 7;
- the state files ignoring their guard: 4;
- the raw log ignoring its guard: 2;
- a bare `git push`: 1;
- `--remotes` with no remote named: 1;
- an unborn HEAD pushed anyway: 1;
- no token shapes: 3;
- no search before staging: 1;
- the submit path not waiting: 1;
- replace refs on: 1;
- the state check bypassed altogether: 8;
- the listing check removed: 1, on macOS as well now.

Round 4's, against the final code, with how many tests each failed:

- the tracked question folding ASCII alone: 1 (the Unicode-fold case; on this host the end-to-end U1
  tests still refuse, through the same directory on disk);
- the same-directory check removed: 1;
- git's environment passed through: 5;
- the walk put back, as it was: 2, with 371 of 3,000 inspections refused;
- a transient refusal kept for the session: 1;
- the raw directory checked once per log rather than per frame: 1 (a frame written through the link);
- frames skipped rather than held: 7;
- no limit on what is held: 1;
- `close()` silent: 1;
- `RecordWriter` saying "stayed in the raw directory" again: 1;
- a state read following a link: 1;
- one WARN per file rather than per reason: 1;
- no check for a remote: 1;
- no cached head, or one cached for any head: 1 each;
- an `error:` after exit 1 taken for "nothing found": 1;
- each of the three fail-closed refusals turned into `return true`: 2, 1 and 1;
- the search after staging removed: 1 — round 3's one survivor, now killed by the clean filter;
- round 3's mutants again — the entry exclusion 1, git answering that nothing is tracked 14, state
  files ignoring their guard 5, a bare push 1, `--remotes` unnamed 1, an unborn HEAD pushed 1, no token
  shapes 7, no search before staging 1, the submit not waiting 1, replace refs on 1, the state check
  bypassed 9.

Without `icase`, the `.PS` test fails where `.PS` and `.ps` can coexist: measured on a case-sensitive
APFS image here. Linux CI is such a filesystem, but this branch has not run on CI yet. On a
case-insensitive host the identity check refuses first, and the test skips.

**The guards fail closed but do not stop the records for nothing.** The pathspec lets a commit
proceed without `.ps`, where a refusal would stop it. The gate searches the token and GitHub's shapes,
never a prefix of the token or the user name every GitHub token shares. One positive test pins that
near misses (`ghp_short`, `github_pat_tooShort`, `x-access-token`, `ghx_` and 36 characters) commit.
Two pin that a healthy repository still commits and pushes with a credential stored.

## Accepted costs

- **A pull can still delete the real state directory (F3).** Git treats it as expendable; the tracker
  cannot prevent that. The credential and the raw frames in it are gone. Until the link is replaced
  with a real directory, nothing is stored, committed or pushed.
- **While `.ps` is refused, state files are not written and raw frames live in memory.** Timers, the
  backup marker and the seed ledger skip their writes, each reason said once: a problem opened in that
  window has no start time. Raw frames are held, at most 8,000,000 characters across the log; past
  that they are dropped, and that is said. A crash or a kill loses what is held — `close()` runs only
  when the server stops in order, and then writes what it can. Round 3 skipped the frames outright,
  which discarded originals; that is reversed.
- **A tracked or hand-staged `.ps` file stops everything.** `ls-files` reads the index, so one staged
  by hand is tracked. Every commit and push is refused until `git rm -r --cached .ps` takes it out —
  the owner's to run, on their own history. Round 2 committed around such a file and left it staged.
- **The other ignore rules still depend on `.gitignore`.** With a broken one, reconciliation commits
  `.DS_Store`, `.obsidian/workspace.json`, `.idea/` and `*.iml` as before. A WARN says so at boot.
- **An untracked directory git cannot open is skipped silently (F8).** Reading stderr again would bring
  back the empty commit a linked `.gitignore`'s warning caused.
- **TOCTOU windows remain (N10): every check is of a path, not a held handle.** They sit between the
  state directory's inspection and a write, between a writer's stat of its directories and its open
  (the open itself never follows a link at the file), and between the commit's searches and the
  partial commit, which reads the working tree again; the push searches what was committed. The ignores writer's
  no-follow read and `CREATE_NEW` matter only inside the race (both mutants survive). A FIFO swapped
  in between the store's checks would hang boot (F13). Each needs a process racing the tracker on this
  machine, outside the clone-or-pull model. Handle-based writes (`SecureDirectoryStream`) are a
  follow-up.
- **A hard link needs local write access.** It is a regular file to every check here; git cannot
  deliver one.
- **The gate reads file content, nothing else.** Commit and tag messages are not searched for the
  stored value or the shapes (N6) — a follow-up. A token that is split, or encoded other than by
  percent-encoding, is missed. So is content a clean filter keeps outside the blob (git-lfs). A token
  of another host's shape is found only while it is stored. A stored secret short enough to occur in
  ordinary text would refuse everything — failing closed, but stopping the backups.
- **The first push reads every commit's tree, twice.** The review measured the stored-value search
  alone, on `7ece5f7`:

  | Repository | First push, whole / per batch of 256 | Five outgoing commits | A commit's gate |
  |---|---|---|---|
  | 1,660 commits, 4.1 MB tree | 21.8 s / 3.7 s | 0.12 s | 0.06 s |
  | 5,000 commits, 9.6 MB tree | 139.6 s / 7.6 s | 0.23 s | 0.10 s |

  Round 3 adds the shape search to every batch. Measured again on the same 1,660-commit repository,
  the first push's gate took 50.2 s: 26.3 s for the shapes, 23.8 s for the stored value. On the 5,000
  one it took 307.6 s: 156.6 s and 151.0 s, the slowest single call 9.3 s. Each search is its own git
  call under the 60-second bound. The review extrapolated that a text tree above about 75 MB makes one
  batch exceed it, and the push is then refused at every attempt, because the range shrinks only when
  a push succeeds; until one does, every pass repeats the whole search. The owner's repository (166
  commits, 431 KB) is one batch. Searching each new blob once (`rev-list --objects`, then
  `cat-file --batch`), which the review measured 180–370 times faster, is a follow-up.
- **A token in a file already pushed blocks every later push.** The search reads each outgoing
  commit's whole tree, so the file is found again until a commit removes it, and by then the token was
  public and has to be revoked.
- **Stale remote-tracking refs are trusted.** The range is what `refs/remotes/<remote>/*` lacks. A
  remote deleted and recreated empty under the same name, before a fetch, leaves commits out of the
  range that the push then sends unsearched. The refspec still sends one branch, but not only
  searched commits.
- **`icase` is ASCII-only.** An untracked `.pſ` rests on the identity check, a tracked one on the
  tracked question, which judges every fold, and both on the gate.
- **A link below `.ps` that no writer's path runs through is not reported.** Nothing walks the
  directory any more; such a link misleads no write, and one a pull delivers is tracked, and refused.
- **`isSameFile` follows links.** A tracked link at the root that leads to `.ps` refuses everything,
  as a tracked `.ps` does; nobody has a reason to commit one.
- **Every state write asks git.** Listing the index took about 9 ms for 5,000 entries (measured), at
  every timer, marker and ledger write and at each session's first frame; a frame costs a stat per
  directory.
- **The unreadable-blob check reads git's `error:` prefix** (2.48.1 and 2.53.0). A git that reworded it
  would let an unreadable blob read as clean again; the push itself would then fail, since
  `pack-objects` cannot read it either.
- **Only eight variables are removed from git's environment.** `GIT_CONFIG_PARAMETERS` and
  `GIT_CONFIG_COUNT`, which set config, and `GIT_OBJECT_DIRECTORY` and
  `GIT_ALTERNATE_OBJECT_DIRECTORIES` still pass through; each is the server's own environment, set
  by its owner.
- **A head searched clean is remembered in memory.** A restart searches it once more. A remote-tracking
  ref that moves backward under the same head widens the range without a new search; those commits were
  on that remote already.
- **The identity check's real-path comparison** catches nothing the other checks do not on the POSIX
  cases tested (that mutant survives). It is kept for a directory that is not a link yet leads
  elsewhere, such as a Windows junction (untested).
- **Refusals repeat.** The daily backup retries every minute while due, so a persistent refusal is
  logged at every attempt, beside the backup's own line, as a failing push already was. The merge
  wait and each writer's skip are said once.
- **`adapter/git` depends on `adapter/store`** (`StateDirectory`, `AtomicStateFile`, the
  `TrackedState` port). It is not the first edge between adapters: `adapter/mcp` has imported
  `adapter/web`'s `WatchToken` since 2026-08-06 (#46). Both go one way, `adapter/store` imports no
  other adapter, and dev rules §1 now says so and names both.
- **The pathspec relies on how git reads an exclusion** (v2.48.1, the same on 2.53.0). A git that
  changed it would make the add fail closed.
- **A linked `.gitignore` is committed as a link.** Its target path is published, never what lies
  behind it.

## Outcome

#360 on `fix/360-reconcile-never-stages-ps`.

The first round:

- `0865d3c` the pathspec and the stdout split;
- `daa2f45` the ignore WARN;
- `d24d55b` and `9248472` the ignores writer;
- `ce678cd` a failed status is not a clean tree.

After the first review:

- `feff707` state files owner-only;
- `5b70e90` the content gate;
- `3c61615` the state directory's identity;
- `0f1b2b5` the credential writer;
- `6f43230` `icase`;
- `e598336` F6, F7 and F11;
- `0cbd7fd` the read-only `.gitignore`;
- `09b01dd` the WARN and the KDocs, corrected.

After the second:

- `283893f` the entry excluded (N3, F12);
- `5d0debc` tracked or linked entries refused (N2, N2a, N1);
- `fcb07ea` every state writer asks the directory, and `.ps` is spelled once (N1, N3b, N7);
- `03896cb` one branch to one named remote (N4, N5, M2, M6);
- `db2e1a6` GitHub's token shapes (R1);
- `19ffde0` the working tree searched before staging (M5);
- `32f3d4b` a submit waits out a merge (N11);
- `5d9d41d` replace refs off;
- `8ed4fe6` the listing check pinned on every platform (M4);
- `2dafd11` the KDoc and the WARNs, corrected (M1);
- `e71bb2a` and `03bd6d2` the nits (M7);
- `7f11f59` this page, `SECURITY.md` and dev rules §1 (first published as `ea1357c`, whose `Closes`
  became a `Refs` when round 4 followed).

Round 3's gates were green: 2,012 tests (9 skipped), ktlint, the build, branch coverage (`adapter/git`
81%, 198 of 242; `adapter/store` 85%, 462 of 538) and the guards.

After the third:

- `2d3c09e` every git call through one helper, in its own environment (ENV);
- `0590dac` every index entry judged in any fold (U1);
- `8de36cd` no walk, typed refusals, refused frames held in memory, a state read that never follows a
  link, one WARN per reason, no fail-open default (the Critical, Important 2, the Minors);
- `836c540` an unreadable blob refuses, no search with no remote, a head searched clean not searched
  again, the fail-closed branches pinned (Importants 1 and 3);
- `a8a184d` the `.ps` pathspec spelled once, and the KDoc corrected (the Minors);
- `8f68977` a held orphan list copied under its lock before it is written;
- `49904f8` ktlint over the round's files — its lint checks had grepped a coloured report and never
  matched, so the earlier commits of the round fail ktlint and only the last passes it;
- this page, `SECURITY.md`, the index, the amendment and progress.

Round 4's gates are green: 2,043 tests (9 skipped: 8 C#, and the `icase` test, which needs `.PS` beside
`.ps`), ktlint, the build, branch coverage (`adapter/git` 85%, 225 of 262; `adapter/store` 84%, 535 of
632; `adapter/config` 65%, 25 of 38, at its floor) and the guards. On the case-sensitive APFS image,
where nothing folds, as on Linux, the git and store tests pass, the `icase` test runs, and the seven
that need a folding filesystem skip. 32 mutants of this round's code and of round 3's layers, run
against the final code, all fail a test. The review's two race measurements, against the fix: 0 of
3,000 inspections refused and 0 of 500 sessions short of a frame, three runs each (434 and 2 on the
code before it, here).

U1 was run again as the adversarial review ran it — a real pull, the critic's own drivers on the
tracker's boot jar — on the host (git 2.48.1) and in the tracker's image over a macOS bind mount (git
2.53.0). Both pulls still leave `.pſ/git-credentials`, `.pſ/timers.json` and
`.pſ/raw/orphans/120804.jsonl` in the index. Now `forGit()` answers `Refused(TRACKED)`, the token is
not stored, `git status` is clean, the server's reconcile and push both answer false, another tool's
`commit -a` finds no token, and the remote holds none of the server's state.

Every git behaviour the layers rely on was checked on 2.48.1 here and on 2.53.0 in the tracker's image:

- `git grep -F` with patterns from a file and from stdin, `--cached` and over commits;
- `git grep --untracked` under the scope: exit 1 on a clean tree with the token in `.ps`, 0 with it
  pasted into an untracked note;
- the token shapes' intervals: a token-shaped note matches, the near misses do not;
- the entry exclusion: a healthy add and commit exit 0; with a tracked `.ps` link taken out of the
  index and a directory in its place, the partial commit exits 128 without it and 0 with it;
- `ls-files` with `icase` lists `.PS/x` and, where both can exist, a `.ps` link — not `.ps2/y`;
- the explicit refspec sends `refs/heads/main` alone, with `remote.origin.push` set to
  `refs/heads/*:refs/heads/*` and `push.default=matching`; a bare push (2.48.1) sent `drafts` as well;
- with a second remote holding a commit, `--remotes` lists 0 commits and `--remotes=origin` 1;
- with a replace ref, the search missed the token (exit 1) and found it with
  `GIT_NO_REPLACE_OBJECTS=1` (exit 0), while a push sent the original;
- `rev-parse --git-path`, `--untracked-files=all`, and `icase` in a healthy repository;
- `git grep` over a commit whose blob is missing: `error: … unable to read`, exit 1; whose tree is
  missing: `fatal: unable to read tree`, exit 128; `rev-list --not --remotes=origin` with that ref
  naming no object: `fatal: bad object origin/main`, exit 128; a linked `.gitignore` under
  `grep --untracked`: a `warning:` only, exit 1.

Not verified live. On a records repository whose `.ps` is a real directory with nothing tracked in it,
and whose `.gitignore` is a regular file holding the rule, a rebuilt server should log no refusal at
boot. Its startup reconciliation should succeed, and its next push should carry no
`.ps` path. The owner's repository was checked by names only, before this branch: no `.p*` path in any
commit, no tracked symlink, `.ps` a real directory (#360's newest comment).

**Windows CI found two things the reviews could not (PR #379).**
- **Test setup.** Git writes objects read-only, and Windows will not delete a read-only file. The runner's `core.autocrlf` also made `hash-object` print a warning that the helper read as the object id.
- **A product bug in `GitProcess`.** Windows will not delete a file a process still holds, and `git push` to a local path leaves receive-pack holding the output file after git exits. The cleanup in `finally` then threw, discarding the answer already read, so a push could be reported as one that "could not run". Cleanup is now best effort: a file that will not go is retried by the JVM at exit. `GitProcessTest` pins it, and the test fails against the throwing cleanup.

**#377, after the merge.** Two low findings from round 4's adversarial pass had been inferred from the
code. Both were reproduced on `fix/377-raw-replay-guard` as tests that failed against `41f713e`.

- **A raw session a pull delivered was replayed at boot.** `unprocessed()` listed `.ps/raw` with no
  guard. A session git checked out there was recorded at boot (`recorded=1`), and so was one behind a
  linked raw directory. The work list is now read only where a frame would be written: `forWriting()`,
  then `pathFor("raw")`, the judgement every raw write takes, with no second rule. Git that cannot
  answer does not stop a replay, just as it does not stop a write. Otherwise nothing is read, moved or
  deleted. One WARN per reason gives the reason and how many sessions were left, and never names a
  path below `.ps`. Through a link nothing is listed, so the WARN says that nothing was counted. A
  session left in place is replayed at the first boot that finds `.ps` usable.
- **A session kept its first frame's answer.** Once git tracked a file under `.ps` mid-grading, the
  next frame of the grading in flight was still written to `.ps/raw`. The cache is kept, with a
  stated bound.
  - Cost of the alternative: asking git at every frame took a median of 7.2–8.1 ms, against 0.03 ms
    for the append and 0.007 ms for the per-frame link check. Measured on the host (APFS, git 2.48.1)
    with 500 to 5,000 index entries; not measured in the image.
  - The other two re-check points guard nothing. A re-check when the session completes comes after
    its last frame. One when a write fails never fires, because a pull that adds a tracked file makes
    no write fail.
  - What the cache lets through is the rest of one grading, written to the session's own file. Git
    does not track that file unless the pull delivered that exact name, and the name carries the
    grading's start to the millisecond.
  - The next session asks again, so round 4's "When the writers ask" stands.
- **Tests and mutants.** There are 12 new tests.
  - Nine failed against `41f713e`. The pin of the bound replaced a reproduction that failed there
    too.
  - Two pin what held already: a refused directory with nothing in it says nothing, and git that
    cannot answer does not stop a replay.
  - Each of 12 mutants ran against the whole suite, and all were killed. The number of tests each
    failed:

    | Mutant | Tests failed |
    |---|---|
    | the guard removed | 9 |
    | `forWriting()` removed | 5 |
    | `pathFor("raw")` removed | 3 |
    | `forGit()` in its place | 1 |
    | counting through the link | 1 |
    | a WARN with nothing left | 1 |
    | a WARN at every call | 1 |
    | refused sessions deleted | 7 |
    | the WARN without its count | 1 |
    | the WARN without its reason | 1 |
    | git asked at every frame | 1 |
    | the answer kept for the log rather than the session | 1 |
- **What this pass left.** The review round below closes the first two.
  - The TRACKED reason told the owner to take `.ps` out of the index. After that, a session git delivered
    was an untracked file like the tracker's own, and the next boot replayed it. The WARN named
    `git ls-files .ps`, which lists no folded spelling.
  - A session file that is itself an untracked link was listed, and the reconciler read through it.
  - `orphans()` reads `.ps/raw/orphans` without the guard, at boot and for MCP's `incompleteHistory`.

**The review of PR #395, at `4008e8f`.** An adversarial review found nothing blocking, and CI was green.
On real git 2.48.1 on APFS it measured two things. The replay guard deferred a forged session rather than
blocking it, and two of the branch's own messages were false. Each fix below has a test that failed
against the code before it, on behaviour. Where an API was new, it went in first with nothing behind it.

- **M1: a forged session was deferred, not blocked.** The review measured it end to end:
  - upstream force-added `.ps/raw/<S>.jsonl`, holding algorithm-pass frames;
  - the owner pulled, met the TRACKED WARN, ran its `git rm -r --cached .ps`, and restarted;
  - the boot recorded the forged PASS (`recorded=1`) with no raw-log WARN, and the next reconcile
    committed it.

  `35fc42f` adds `pathsEverTracked()` to `TrackedState`: every path below the state directory that any
  commit a ref reaches holds, as stored. `TrackedStateEntries` answers it with one
  `git log --all -m --root --no-renames --name-only -z`, and judges first segments as it judges the index.
  The raw log asks once a start, and only when a session waits. It never replays a session whose
  `raw/<name>` the history names, in any case. Such sessions are left in place and said once. While git
  cannot say what it has tracked, nothing is replayed, and that is said too. A git that cannot answer
  therefore defers a replay where the first pass let it through. A write still goes ahead, but unknown is
  not "never delivered".

  The cost was measured on this host, as the median of 21 runs, on synthetic records histories built with
  `git fast-import`:

  | History | The question | `unprocessed()`, one session waiting | Nothing waiting |
  |---|---|---|---|
  | 166 commits, 460 KB tree (the owner's size) | 10.7 ms | 20.1 ms | 7.9 ms |
  | 1,660 commits, 4.5 MB tree (the review's size) | 33.6 ms | 40.7 ms | 8.4 ms |

  With nothing waiting, the history is not read, so the last column is the index question alone. The
  plainer `git log --all --name-only -z --format=` took 10.2 and 32.3 ms. `-m`, `--root`, `--no-renames`
  and the rest therefore cost 0.5–1.3 ms. The question runs once a start. It was not measured in the image.
- **M2: the way out was false for folded names.** `.PS/raw`, `.Ps/raw`, `.pſ/raw`, `.Pſ/raw`, `.PS/RAW` and
  `.pſ/RAW` all land on `.ps/raw` and are refused as TRACKED. But `git ls-files .ps` listed none of them,
  and `tracksAnything()` stayed true after `git rm -r --cached .ps`.
  - `bab8bb8` changes the reasons to give `git ls-files -- ':(icase).ps' ':(icase).pſ'` and
    `git rm -r --cached --ignore-unmatch -- ':(icase).ps' ':(icase).pſ'`. `:(icase)` folds ASCII alone, so
    `ſ` has its own pathspec.
  - TRACKED says to delete from disk what git put there before untracking it. `--cached` stays, so the
    server's own files, such as a credential staged by hand, are only untracked.
  - A test runs both commands as the owner reads them, for eight spellings, with real git.
- **M4: held sessions were invisible to MCP.** `0de3f66` adds `RawSessionLog.unreplayed()`, which keeps what
  the last start left. `incompleteHistory` carries it as `sessionsNotReplayed`, or as
  `rawDirectoryNotListed` where nothing could be counted. `docs/mcp.md` and its twin describe both keys.
  `ec783c3` counts what a start leaves in one place.
- **L1: a session file that was a link** (`.ps/raw/<S>` pointing at frames in the tree) was recorded.
  `8c24551` lists only regular files, says the rest once and counts them as left. The reconciler now reads
  without following a link at the file.
- **L2: the link check ran before the git call**, so swapping `.ps/raw` for a link during the call let a
  session through. `fca29fc` asks git first and runs the link check just before the listing.
- **L3 and L5: wording** (`bab8bb8`).
  - A test KDoc says "between two frames" rather than "never".
  - The WARN for a directory that was not listed names no link, since it also serves one that could not
    be inspected.
  - HOLDS_A_LINK says to move what lies behind the link into a real directory before removing it, and
    NOT_THE_DIRECTORY says the same.
- **L4: an exact-name collision, where the cached verdict's bound was measured.** It needs no code. The
  review found that a pull delivering a tracked file under the in-flight session's very name overwrites the
  ignored file. The two real frames already written are lost, and the tracked file takes the rest of that
  grading. The pull needs that exact name, which carries the grading's start to the millisecond.
- **Pins.** `8362c46` adds three tests: `--root` under `log.showRoot=false`, which an owner's global config
  can set; the `raw` segment of a history path; and the unanswered history's WARN said once.
- **Mutation.** 32 mutants ran, each against the whole suite, and all were killed:

  | Mutant | Tests failed |
  |---|---|
  | links checked before git answers | 1 |
  | entries that are not regular files listed | 3 |
  | passed-over entries said at every start | 1 |
  | the reconciler reading through a link at the file | 1 |
  | no session excluded by git's history | 6 |
  | an unanswered history taken for an empty one | 2 |
  | git asked when nothing waits | 1 |
  | a history name matched in its case alone | 1 |
  | the `raw` segment unchecked | 1 |
  | the `raw` segment matched in its case alone | 1 |
  | the unanswered history's WARN at every start | 1 |
  | the known sessions' WARN at every start | 1 |
  | no `-m` | 1 |
  | no `--all` | 2 |
  | no `--root` | 1 |
  | paths outside the state directory answered | 2 |
  | the state directory by its exact name alone | 1 |
  | a failed `git log` taken for an empty history | 2 |
  | the history never handed to the log | 20 |
  | the listing command without `ſ` | 3 |
  | the untracking command without `ſ` | 8 |
  | the untracking command without `--ignore-unmatch` | 13 |
  | TRACKED without deleting what git put there | 1 |
  | HOLDS_A_LINK removing the link alone | 1 |
  | the uncounted WARN naming a link | 1 |
  | what a start left never recorded | 6 |
  | entries passed over not counted as left | 1 |
  | replayed sessions counted as left | 2 |
  | a raw directory that was not listed, not flagged | 2 |
  | `sessionsNotReplayed` written with nothing to say | 1 |
  | `rawDirectoryNotListed` written with nothing to say | 1 |
  | `incompleteHistory` absent when only sessions were left | 2 |
- **Gates**, all exit 0:
  - check;
  - test: 2,181 JUnit tests in 161 classes, 0 failures, 9 skipped as before, and node 4 of 4;
  - build;
  - `verifyBranchCoverage`: `adapter/store` 85% (641 of 754), `adapter/git` 85% (232 of 270), every package
    at or above its floor;
  - guards: 12 of 12.
- **What remains.**
  - `orphans()` reads `.ps/raw/orphans` without the guard (M3, for #378). The review measured three
    effects:
    - a tracked orphans file forges every answer's `incompleteHistory`;
    - a tracked link counts lines outside `.ps`;
    - a link to `/dev/zero` exhausts the heap on each call (183–954 ms).
  - If the owner untracks a session git delivered without deleting it, the file stays on disk. It is
    never replayed, and it is counted in `sessionsNotReplayed` at every start until removed.
  - A git that cannot say what it has ever tracked defers every replay. If the server cannot run git —
    a repository it may not read, a `safe.directory` refusal in a container — the work list waits until git
    answers, and the WARN and `sessionsNotReplayed` say so.
  - The history covers every ref, not the reflogs. A forged commit reset away from every ref, with its
    file kept on disk untracked, is not named.
  - A directory swapped for a link after the listing, but before the reconciler reads it, is read through;
    the file itself never is. That needs a process racing the boot on this machine.
  - This round was not measured in the image, CI has not run it, and it was not verified live.
