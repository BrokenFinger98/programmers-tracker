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

## Decision

Five layers, each covering what the one before cannot:

1. **The pathspec.** Reconciliation checks, stages and commits with
   `. :(exclude,glob,icase)[.]ps :(exclude,glob,icase)[.]ps/**`, the entry and what is under it,
   whatever `.gitignore` says. On an unborn branch it is still a root commit. The submit path stages
   only the record's paths, and drops any whose first segment is `.ps` in any case.
2. **The state directory, what it is and what is in it** (`StateDirectory`, `adapter/store`). Absent,
   `.ps` is created as a real directory. Present, it must be:
   - listed in the root by exactly that name;
   - a directory without following a link;
   - at the real root plus `.ps` as its real path;
   - free of symbolic links anywhere below it, found by a walk that follows none;
   - free of anything git tracks there, the entry included, in any ASCII case:
     `git ls-files -z -- :(glob,icase)[.]ps :(glob,icase)[.]ps/**`. The question is the `TrackedState`
     port, declared in `adapter/store` and answered by `adapter/git`'s `TrackedStateEntries`.

   Otherwise, or when git cannot say, `CommandLineGitSync` runs no commit, reconciliation or push, and
   `GithubRemote` stores no credential and wires nothing. Git is still asked what it tracks, and where
   a push would go. One WARN names the reason, never content, and the way out: `git rm -r --cached .ps`,
   and commit that.
3. **The state writers.** The credential store, the raw frames, the timers, the backup marker and the
   seed ledger all ask the same question before writing. The ledger now writes through
   `AtomicStateFile` like the others: a temporary file renamed over the old one, which replaces a link
   rather than writing through it, and starts owner-only. `gitConfig()` offers the helper only for a
   regular file. While the directory is refused, a write is skipped with one WARN per writer. When
   git cannot say what it tracks, the write goes ahead. `.ps` is spelled once, `StateDirectory.NAME`.
   The writers, the ignore rule, the credential path and both pathspecs derive from it, and one test
   pins that they agree.
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
     a search that fails, and outgoing commits that cannot be listed. Each refusal is one WARN that
     names why and never the token, and a false. A match says to revoke the token on GitHub and remove
     it from history.
5. **The push.** `git push <remote> HEAD:refs/heads/<branch>`: the current branch alone, named on the
   command line, so `remote.<r>.push` and `push.default` send nothing else. The remote is
   `branch.<b>.pushRemote`, else `remote.pushDefault`, else `branch.<b>.remote`, else `origin`. The
   commits searched are `rev-list HEAD --not --remotes=<remote>`. An unborn HEAD is nothing to push and
   says nothing; a detached one is not pushed, and says so. Every git call runs with
   `GIT_NO_REPLACE_OBJECTS=1`, so the search reads the objects a push sends.

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
| F2 link at the store | the walk; the writer replaces a link; the helper skips one | `stores nothing when the credential path is a link`; `points git at nothing when the store is a link`; `a credential store that is not a regular file refuses every commit and push` |
| F3 `.ps` a link | identity | `a state directory that is a link into the tree refuses every commit and push`; `stores no credential into a state directory that is a link` |
| F4 `.PS` | identity on a case-insensitive volume; `icase` where both can exist | `a state directory the filesystem folds from another case refuses every commit`; `the state directory is left out in any case` |
| F5 another tool's commit | the push gate | `a commit another tool made with the push token is never pushed` |
| N2 a tracked store | tracked entries | `a credential store a pull delivered stops every commit and push for what it is`; `stores no token over a store git tracks` |
| N2a a one-letter store | the same refusal, for what it is, before any search | `a credential store a pull delivered stops every commit and push for what it is` |
| N1 a link inside `.ps` | the walk; the raw log asks first | `a link inside the state directory stops every commit and push`; `no frame is written through a link where the raw directory was` |
| N7 a link at the ledger | the writers ask first | `the ledger writes nothing through a link` |
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

The new unit tests for `StateDirectory`, `TrackedStateEntries` and the writers' guard were red as
compile errors first, since the API they call did not exist. Three tests pin what held already and
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
- **While `.ps` is refused, the tracker keeps no state.** Raw frames, timers, the backup marker and
  the seed ledger are skipped, each said once. A grading captured in that window is recorded from the
  grading itself, without its raw copy. That is a gap in "keep the original" (dev rules §2.4),
  bounded by the refusal's WARN, and taken because the only place left to write was through the link,
  or into the tracked path, that caused the refusal. `RecordWriter` then logs that the frames "stayed
  in the raw directory", which they never reached.
- **A tracked or hand-staged `.ps` file stops everything.** `ls-files` reads the index, so one staged
  by hand is tracked. Every commit and push is refused until `git rm -r --cached .ps` takes it out —
  the owner's to run, on their own history. Round 2 committed around such a file and left it staged.
- **The other ignore rules still depend on `.gitignore`.** With a broken one, reconciliation commits
  `.DS_Store`, `.obsidian/workspace.json`, `.idea/` and `*.iml` as before. A WARN says so at boot.
- **An untracked directory git cannot open is skipped silently (F8).** Reading stderr again would bring
  back the empty commit a linked `.gitignore`'s warning caused.
- **TOCTOU windows remain (N10): every check is of a path, not a held handle.** They sit between the
  state directory's inspection and a write, and between the commit's searches and the partial commit,
  which reads the working tree again; the push searches what was committed. The ignores writer's
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
- **`icase` is ASCII-only.** `.pſ` rests on the identity check and the gate.
- **The identity check's real-path comparison** catches nothing the other checks do not on the POSIX
  cases tested (that mutant survives). It is kept for a directory that is not a link yet leads
  elsewhere, such as a Windows junction (untested).
- **The search after staging has no test of its own.** With it removed, every test passes: the
  search before staging reads the same content, so it differs only inside the window between the two,
  which no test holds open. It is kept for that window.
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
- this page, `SECURITY.md` and dev rules §1.

Gates green: 2,012 tests (9 skipped), ktlint, the build, branch coverage (`adapter/git` 81%, 198 of
242; `adapter/store` 85%, 462 of 538) and the guards. Every git behaviour the layers rely on was
checked on 2.48.1 here and on 2.53.0 in the tracker's image:

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
- `rev-parse --git-path`, `--untracked-files=all`, and `icase` in a healthy repository.

Not verified live. On a records repository whose `.ps` is a real directory with no link and nothing
tracked in it, and whose `.gitignore` is a regular file holding the rule, a rebuilt server should log
no refusal at boot. Its startup reconciliation should succeed, and its next push should carry no
`.ps` path. The owner's repository was checked by names only, before this branch: no `.p*` path in any
commit, no tracked symlink, `.ps` a real directory (#360's newest comment).
