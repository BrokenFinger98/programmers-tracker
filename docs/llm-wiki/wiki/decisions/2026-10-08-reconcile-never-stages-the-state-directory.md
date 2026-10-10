---
type: decision
project: programmers-tracker
tags: [security, git, credentials, links, storage, push]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-10
sources: [raw/sessions/2026-10-07-the-readers-that-followed-links.md, raw/sessions/2026-10-08-the-token-gate-took-four-rounds.md, raw/sessions/2026-10-08-seventeen-prs-through-one-queue.md, raw/sessions/2026-10-10-the-limit-the-load-and-the-last-five.md]
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
- **N6.** Commit and tag messages are not searched. *Resolved at the push by #375: the commits a push
  sends are read, messages, authors and committers, and no tag is sent.*
- **Replace refs.** The gate read a replacement object while the push sent the original.
- **N11.** The submit path had no merge wait.
- **N10.** Every check is of a path at one moment, and the write resolves it again. With no store, the
  gate also had nothing to search for. *Closed for the writers of `.ps` by #374, where the platform gives a
  directory handle: a writer holds the directory it checked, open, and writes in it by name — see
  [[decisions/2026-10-08-state-is-written-in-the-directory-it-checked]].*

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
     refused commit leaves nothing staged. After staging, `git grep --cached` searches the index. *⚠️
     Superseded by [[decisions/2026-10-08-each-gate-searches-what-its-destination-lacks]] (#376): what a
     commit adds — its new paths, and its blobs less what HEAD's tree holds — is found by `add` on a copy
     of the index and searched by the push's scan, before anything is staged, and once.*
   - Before every push, each commit the push would send is searched, 256 to a search. *⚠️ Superseded
     by [[decisions/2026-10-08-the-push-gate-reads-each-object-once]] (#373): each blob a push would
     send is read once, by `cat-file`, and searched in the JVM.*
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
     again by the same server. *Since #378, for the same destinations and the tips they hold as well.*
5. **The push.** `git push <remote> HEAD:refs/heads/<branch>`: the current branch alone, named on the
   command line, so `remote.<r>.push` and `push.default` send nothing else. The remote is
   `branch.<b>.pushRemote`, else `remote.pushDefault`, else `branch.<b>.remote`, else `origin`; the
   branch goes to its own name there, and `branch.<b>.merge` is not consulted. A remote with no `url`
   and no `pushurl` is nowhere to push: nothing is searched, and the WARN says so. The commits searched
   are `rev-list HEAD --not --remotes=<remote>`. *⚠️ Superseded by #376: the range leaves out what the
   push URLs say they hold, through `ls-remote`, never what a remote-tracking ref remembers; and a URL as
   the branch's remote is pushed to (#378).* An unborn HEAD is nothing to push, says nothing and
   answers true. *Since #372 the daily backup records a day only when reconciliation succeeded as
   well, so an unborn branch whose reconciliation was refused is not recorded — see the Outcome.* A
   detached one is not pushed, and says so.
6. **Git's environment** (`GitProcess`). Every git call of the tracker runs through one helper, with
   `GIT_TERMINAL_PROMPT=0` and `GIT_NO_REPLACE_OBJECTS=1` — so the search reads the objects a push
   sends — and, since #372, `LC_ALL=C`, so git says its words in English and `grep -E` reads bytes as
   bytes; and without `GIT_DIR`, `GIT_WORK_TREE`, `GIT_COMMON_DIR`, `GIT_INDEX_FILE` and the four
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
  back the empty commit a linked `.gitignore`'s warning caused. *Since #372 it is skipped and said,
  once a day while it lasts — see the Outcome.*
- **TOCTOU windows remain (N10): every check is of a path, not a held handle.** They sit between the
  state directory's inspection and a write, between a writer's stat of its directories and its open
  (the open itself never follows a link at the file), and between the commit's searches and the
  partial commit, which reads the working tree again; the push searches what was committed. The ignores writer's
  no-follow read and `CREATE_NEW` matter only inside the race (both mutants survive). A FIFO swapped
  in between the store's checks would hang boot (F13). Each needs a process racing the tracker on this
  machine, outside the clone-or-pull model. Handle-based writes (`SecureDirectoryStream`) are a
  follow-up. *Since #374 the first two are closed for the writers of `.ps` on Linux and macOS, the
  tracker's image over a macOS bind mount included: `.ps` and the directories below it are held open from
  before the check to the write. Measured on main first, a swap while git answered put the timers document
  and the push credential in the tracked directory a link led to. Windows has no handle and keeps both
  windows; the commit's window is git's and stays — see
  [[decisions/2026-10-08-state-is-written-in-the-directory-it-checked]].*
- **A hard link needs local write access.** It is a regular file to every check here; git cannot
  deliver one.
- **The gate reads file content, nothing else.** Commit and tag messages are not searched for the
  stored value or the shapes (N6) — a follow-up. *Since #375 a push reads the commits and the names it
  would send, and sends no tag; the commit side still reads file content alone.* A token that is split,
  or encoded other than by percent-encoding, is missed. So is content a clean filter keeps outside the blob (git-lfs). A token
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
  `cat-file --batch`), which the review measured 180–370 times faster, is a follow-up. *⚠️ Resolved by
  #373, the page decision 4 links: a first push of 5,000 commits went from 252.5 s to 0.52–0.63 s,
  measured the same way on both.*
- **A token in a file already pushed blocks every later push.** The search reads each outgoing
  commit's whole tree, so the file is found again until a commit removes it, and by then the token was
  public and has to be revoked. *⚠️ No longer since #373: an object the remote's branches already reach
  is not read again, as the push does not send it again.*
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
- **The records repository's hooks inherit `LC_ALL=C` (#372).** A hook the owner keeps there runs in
  the C locale now. Measured here: bash's `${#x}` counts bytes (6 for a two-syllable Hangul word, 2
  under `en_US.UTF-8`), and Ruby's default external encoding becomes US-ASCII; Python 3 stays UTF-8.
  For git itself the pin changes nothing in the tracker's image: no `git.mo` is in it, so its git has
  no translations, and the review found glibc's regex unaffected by the locale. Its hooks still run
  in the C locale there.
- **A head searched clean is remembered in memory.** A restart searches it once more. A remote-tracking
  ref that moves backward under the same head widens the range without a new search; those commits were
  on that remote already.
- **The identity check's real-path comparison** catches nothing the other checks do not on the POSIX
  cases tested (that mutant survives). It is kept for a directory that is not a link yet leads
  elsewhere, such as a Windows junction (untested).
- **Refusals repeat.** The daily backup retries every minute while due, so a persistent refusal is
  logged at every attempt, beside the backup's own line, as a failing push already was. The merge
  wait and each writer's skip are said once. *Since #390 the attempts back off, a minute to an hour
  apart — see the Outcome's last note.*
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

⚠️ (superseded the same day by the live check below) Not verified live. On a records repository whose
`.ps` is a real directory with nothing tracked in it, and whose `.gitignore` is a regular file holding
the rule, a rebuilt server should log no refusal at boot. Its startup reconciliation should succeed, and
its next push should carry no `.ps` path. The owner's repository was checked by names only, before this
branch: no `.p*` path in any commit, no tracked symlink, `.ps` a real directory (#360's newest comment).

**Verified live 2026-10-08 at 14:55 KST.** PR #379 was squash-merged as main `ccca9e6` and the container
rebuilt (raw/sessions/2026-10-08-the-token-gate-took-four-rounds.md). Measured:

- **The boot.** Healthy, with no WARN or ERROR line, so no refusal. The real `.ps` on the macOS bind mount
  was judged usable, and the startup reconciliation ran.
- **The records repository.** HEAD was still `7e144fa`, and the status was clean. No `.p*` path appeared
  in any commit on any ref, nothing under `.ps` was tracked, and `.ps` was a directory.
- **The tools.** The 36 MCP snapshot files were identical to the snapshot taken after #364.

Not yet exercised: a real push through the gate. The boot had nothing to commit or push (inferred from
the unchanged HEAD and the clean status).

**Windows CI found two things the reviews could not (PR #379).**
- **Test setup.** Git writes objects read-only, and Windows will not delete a read-only file. The runner's `core.autocrlf` also made `hash-object` print a warning that the helper read as the object id.
- **A product bug in `GitProcess`.** Windows will not delete a file a process still holds, and `git push` to a local path leaves receive-pack holding the output file after git exits. The cleanup in `finally` then threw, discarding the answer already read, so a push could be reported as one that "could not run". Cleanup is now best effort: a file that will not go is retried by the JVM at exit. `GitProcessTest` pins it, and the test fails against the throwing cleanup.

**#372 followed up F8 and decision 5's unborn push** (branch `fix/372-backup-needs-reconcile`):

- **F8 is said.** Every status the tracker runs has its stderr read for
  `warning: could not open directory '<path>': <reason>` — measured on 2.48.1 for a directory at
  `000` or `-wx`, for the subdirectory of one at `r--`, and for a tracked directory at `000`, where a
  changed file was not listed either. Each directory is named with git's reason and never what it
  holds — once per instance at first, once a day since the review below. It stays a warning:
  reconciliation commits the rest and answers true, so the
  empty commit this page's cost was about does not come back. Git's other such line,
  `unable to access '<path>'`, is left alone: it named the `.gitignore` of a directory git can list
  but not enter, and a linked `.gitignore` — files whose rules then do not apply, which leaves no
  record out. Not caught: a *tracked* directory at `r--` hid a changed file in it behind a line with
  no `warning:` (`<path>: Permission denied`), measured.
- **Git's environment pins `LC_ALL=C` (layer 6), for two reasons.**
  - *Translations.* Homebrew's git 2.48.1 translated `could not open directory` under
    `ko_KR.UTF-8`, the language this tool's users run it in, and under `de_DE.UTF-8` the prefixes
    too: a grep over an unreadable blob said `Fehler:` and exited 1, so the gate's `error:` check
    read it as nothing found — the reworded git this page's costs name, reached by a locale instead.
    Under Korean the prefix stays `error:`.
  - *Bytes* (found by the review of #389). In a UTF-8 locale macOS's regex stops at a byte that is
    not UTF-8, so the gate's `git grep -E` missed a token after one on the same line. A note holding
    `caf`, the Latin-1 byte `0xE9`, a space and a token-shaped string: exit 1 and nothing on stderr
    under `en_US.UTF-8`, 0 under `C` (measured here; the review saw the same with Apple's git and no
    difference on glibc). Before the pin, `reconcile()` committed that note and answered true. The
    token before the byte, or on the next line, is found either way, and so is the stored value,
    which is searched as fixed strings.

  Paths and commit messages are not translated: the suite's Korean subjects and problem paths pass
  under the pin.
- **The daily backup records a day only when reconciliation succeeded as well** (the amendment to
  [[decisions/2026-08-06-wire-git-into-the-pipeline]]). A branch with no commit yet and nothing to
  commit still counts; one whose reconciliation was refused, which decision 5's true would have
  recorded, does not.

Each test was red first: the directory said once by name while the rest is committed, and the same
under Korean (`List is empty`); git's answer in English under Korean and German (it answered in
Korean). Mutants, each failing a test: the pin removed (2 tests), the detector removed (2), the
directory said at every status (1), and the warning read as a change, which failed the second
reconciliation (1).

**After the review of #389** (approved, nothing blocking):

- The bytes reason above, pinned through the real `reconcile()` with `en_US.UTF-8` inherited: a
  refusal and no commit. Red with the pin removed (`expected:<false> but was:<true>`). On Linux the
  test passes either way, and its KDoc says so.
- Two mutants of the F8 detector had survived — a dedupe key shared by every directory, which
  silences all after the first, and a pattern that stops at a quote. A test now seals `it's/` and a
  directory with a space and Hangul in its name; each mutant fails it (expected 2, was 1).
- F8 is said once a day while it lasts, by the date on the process clock, not once per process. The
  review measured `runIfDue()` recording every day around a directory at `000`, with the warning
  said on the first day only. The day is still recorded: holding it would retry every minute for what
  only the owner can fix. The exception is stated in `GitSync` and `DailyBackup.performed`, and pinned
  at both layers.
- The F8 warning no longer says nothing under the directory is pushed: in a tracked one, what was
  committed before still goes up.
- **Known cost, left to #390.** A held day now retries every minute, so the content gate's
  `CREDENTIAL_FOUND` warning, and a failing pre-commit hook, repeat every minute while it is held,
  where before the day was recorded and they came once a day. #390 takes it with the per-minute
  warnings of a repository with no remote. *Done in #390: a held day is tried on a backoff, a minute
  to an hour apart, about 29 times a day —
  [[decisions/2026-10-08-a-backup-that-did-not-count-backs-off]].*

Mutants of this round, each failing a test: the pin removed again (3 tests, the bytes test among
them); the date ignored, so once per process (2); the dedupe removed (3); the directory holding the
day, by reading the warning as a change (4); and the two above (1 each).

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

**#376 and #378, the gate's range and scope** (the #376 page, linked from decision 4; branch
`fix/376-gate-range-and-scope`). Two of round 4's findings, and two of #378's:

- **The push range** leaves out what each push URL says it holds, through `ls-remote`, not what
  `--remotes=<remote>` remembers. A remote re-created under the same name, repointed with `set-url`, or
  given a `pushurl` apart from its `url`, was sent a token pushed once, unsearched; each is now refused, in
  a test that was red before. A destination that cannot answer is not pushed to, and that is said once.
- **The cache of a clean search** is keyed on what was searched: the destinations, the tips they held and
  the store. The gate critic's C1 — `set-url` and `fetch --prune`, then the same server sending a history
  token to the new remote — is a test that was red before.
- **A URL as a branch's remote** is pushed to, and counts as a remote for the daily backup.
- **A commit is searched for what it adds** — its new paths, matched as bytes, and its blobs less what
  HEAD's tree holds — found by `add` on a copy of the index, so M5's guarantee holds for every encoding
  and a refused file is never left staged. A file named with a token, and a token in UTF-16 text, are
  refused at the commit now, where only the push refused them.
- **What HEAD already holds is said, not refused**: a pull that brings in a token-shaped string no longer
  refuses every reconciliation after it.

**#378, with the re-check of #377 folded in** (branch `fix/378-held-frames-and-orphans`, written on #377's
`f807f77` and replayed onto main `258ed10` before its first push). #378's git items 1 and 4 are #376's.

- **M3, raised to High: `orphans()` read what no write would touch** (`d4de07c`). Every answer asks for the
  orphans, at boot and for MCP's `incompleteHistory`, and they were read with no guard. Two reviews measured
  what that let through:
  - a pulled `1.jsonl -> /proc/self/fd/1` hung the boot and every MCP call in the deployed image (the review
    of #387);
  - a link to `/dev/zero` exhausted the heap on each call, in 183–954 ms (the review of PR #395);
  - a tracked orphans file forged every answer's `incompleteHistory`, and a tracked link counted lines
    outside `.ps` (the same review).

  The orphans are now read as the work list is: `forWriting()` first, then `pathFor("raw", "orphans")`, and
  git's history decides which files are read. A file is opened only if it is regular, judged without
  following a link, and holds 16 MiB at most, and it is opened without following a link. The bound is there
  because every answer counts the lines again, and a file that is one long line would be read whole.

  What is passed over is counted and never opened. Under a refused `.ps` the files are counted by name where
  no link is on the way, and a directory that could not be listed is flagged instead. MCP carries the two as
  `orphanFilesNotRead` and `orphansNotListed`, as it carries `sessionsNotReplayed` and
  `rawDirectoryNotListed`, and `docs/mcp.md` and its twin describe them. Each refusal is said once, with its
  reason.

  Ten tests failed against `f807f77`, given the new port types with nothing behind them. The FIFO, the link to
  one and the MCP call over them timed out (5 s, 5 s, 10 s). The others read the forged frames, and the
  unlisted case had no `incompleteHistory`. Five more pin the WARNs and the one question to git.
- **Item 3: an orphan whose file is a link** (`b6877b8`). Decision: the frame is kept, and the link is never
  replaced.
  - Before, the append, which never follows a link, threw `Too many levels of symbolic links`, and the
    frame was lost.
  - Now the frame is held in memory like a refused one, and written once a regular file or nothing stands
    there. The WARN is said once and never says where the link leads.
  - The link is left alone. The file is append-only, and a link there stands for history a replacement
    would drop.
  - Found on the way: the throw reached the next grading too. Its first frame releases what is held
    (`append` → `decided()` → `releaseHeld()`), so a held orphan stopped a live capture. The release now
    passes over a lesson whose file is not a regular file.
  - Three tests, each red against `d4de07c`; the mutation check added a fourth, for a link that leads
    nowhere.
- **Item 2: held frames and the gradings in flight** (`aa809c8`). While `.ps` was refused, runs set aside and
  orphans held in memory shared the 8,000,000-character budget with the gradings in flight. Once they filled
  it, a submit's frames were dropped, and `complete()` threw `NoSuchFileException`, so the attempt had no raw
  copy. A test reproduced it.

  Measured with the fixtures' frames, each held as its own string (JDK 25.0.3), the full budget retains
  16.1 MB of heap. That is 2.01 bytes a character, because the Korean result strings make each frame UTF-16.
  A run's frames are 0.9–1.6 K characters, a submit's 2.1–2.9 K, and the largest capture is 7.8 K.

  The options:
  - one budget, as it was, is the loss above;
  - **a quarter kept for the gradings in flight** was chosen. Runs set aside and orphans hold three quarters
    at most, and going over that is said once. That share fills only after some 3,700 runs or more are held
    while `.ps` stays refused, and a refused `.ps` blocks every commit and push meanwhile;
  - spilling to the tool's own state directory was not chosen. That mount is documented as credentials
    only. A spill would also add a configured path, and a drain across filesystems that needs its own
    duplicate check after a crash.

  Accepted: what is held is lost if the server stops while `.ps` is refused. The first WARN says so, and
  `close()` counts what was lost.
- **The critic's re-check of #377 at `f807f77`** (`011255c`). Four findings were measured on real git, and
  the fifth is accepted.
  - **F1, Medium: the reflogs.** A forged commit that every ref drops was replayed when the owner skipped
    "delete from disk first". The critic measured four routes, each giving `recorded=[131528, 120804]`:
    - a reset, then a force-push;
    - an attacker's force-push, then the owner's plain `git pull --rebase`;
    - `git filter-repo --invert-paths --path .ps`;
    - `git fetch --depth 1`.

    `--reflog` in the history question closes the first two. Both are pinned at the git level, and the
    first end to end. Its cost, as the median of 21 runs with a reflog entry for every commit on `main` and
    `HEAD`, was 9.5 → 10.1 ms on 166 commits and 30.5 → 35.8 ms on 1,660.

    Expiry stays open, since unreachable entries go after 30 days by default, and so do filter-repo and a
    shallow fetch. The TRACKED reason and `SECURITY.md` therefore make the delete mandatory, and say why:
    the server tells a delivered file from its own only while git's history names it.
  - **F2, Low: the reason.** `pathsEverTracked()` answers `Known(paths)` or `Unanswered(reason)`. The reason
    is git's exit code and its own first line, cut at 200 characters, or the timeout, or the kind of
    exception when git could not be started. Both WARNs that hold sessions or orphans give it, and neither
    gives any content.
  - **F3, Low: not counted.** Of the three options, one WARN at each start and no count was chosen. A session
    git delivered is never replayed, and is not counted in `sessionsNotReplayed`. An orphans file git has
    known is neither read nor counted. What git delivered is no gap in what this server captured. A count,
    or a separate key such as `deliveredByGit`, would mark every answer for as long as the file stays, and
    the file is the owner's to delete, which the WARN tells them.
  - **F4, Low: the docs.** `docs/mcp.md` said "four cases" and left out the file that is not regular. It now
    names the four counted cases: not the tracker's own, git tracking something there, git unable to say
    what it has tracked, and a file that is not regular. It also says that sessions known to git are not
    counted. The twin follows.
  - **F5, Low, accepted.** A `.ps/raw` swapped for a link while git answers the history question, after the
    listing, is read through, because `NOFOLLOW_LINKS` covers the last component only. It needs a process
    racing the boot on this machine, and a file at the link's target with exactly the name of a session the
    listing found.

  Every new test for the four failed against the code before `011255c`, given its new history type with
  nothing behind it. Those that change a count were red on the old count.
- **Tests.** The four fixes add 25 tests and rewrite 3. The mutation check below adds 10 more and one
  assertion (`f582287`).
- **Mutation.** 51 mutants of this round's code ran against the store, git-history, MCP, application and
  config tests, 1,175 of them, and 39 failed a test. One of those, a share never freed once its frames are
  written, failed a pin that was then in the tree and is now in `f582287`. Twelve passed every test, and so
  did two more written for F2 after the run. Ten of those 14 now fail a test written for each. The last four
  were run against the whole suite as well, and pass it. Each mutant, with the number of tests it failed in
  the run that first killed it:

  | Mutant | Tests failed |
  |---|---|
  | `orphans()` without asking whether `.ps` is refused | 2 |
  | a refused orphans path listed anyway | 3 |
  | a FIFO or a device opened (no regular-file check) | 2, each by its timeout |
  | no size bound | 1 |
  | the attributes read through a link | none, see below |
  | the frames opened through a link | none, see below |
  | both of those | 2 |
  | an unlisted orphans directory not flagged | 2 |
  | orphans under a refused `.ps` not counted | 3 |
  | under a refused `.ps`, counted through a linked orphans directory | 1, pinned |
  | an orphans file git has known read | 1 |
  | git asked when no orphans file waits | 1 |
  | an unanswered history taken for an empty one | 1 |
  | files left unread while git cannot say, not counted | 1 |
  | each of the three orphans WARNs said at every call | 1 each |
  | orphans git has known never said, or counted as unread | 1 each |
  | `orphanFilesNotRead` never written, or written with nothing to say | 1 each |
  | `orphansNotListed` never written | 1 |
  | `orphansNotListed` written with nothing to say | 1, pinned |
  | `incompleteHistory` absent when orphans were only passed over, or only unlisted | 1 each |
  | an orphan written through a link | 2 |
  | held orphans released through a link | 2 |
  | the link said at every orphan | 1 |
  | a link to a regular file taken for one | 3 |
  | a link to nothing taken for no file, so the append threw | 1, pinned |
  | no `--reflog` | 3 |
  | git's reason not carried | 1 |
  | git's line not cut at 200 characters | 1, pinned |
  | no reason for a git that could not start, or for a port that threw | 1 each, pinned |
  | a timeout not named | none, see below |
  | an empty first line taken for git's reason | none, see below |
  | either WARN without the reason | 1 each |
  | sessions git delivered counted as left, or never said | 1 each |
  | TRACKED without "without fail", or without why | 1 each |
  | a history path matched by its first segments, so `raw` alone excluded every session, and `raw/orphans` every orphans file | 2, pinned |
  | a history path matched in its case alone | 1 |
  | settled frames allowed the whole budget | 2 |
  | orphans held against the whole budget | 2 |
  | runs set aside never bounded by the share | 1 |
  | settled frames written still charged to the share | 1 |
  | a dropped run still charged to the whole budget | 1 |
  | a dropped run still charged to the share | 1, pinned |
  | settled frames not charged to the whole budget | 1, pinned |
  | a frame refused over the share still charged to it | 1, pinned |

  The four left:
  - **The orphans reader's two link checks.** One judges the file without following a link, and the open
    never follows one. Either alone stops a link, so removing one fails nothing, and removing both fails two
    tests. The race between them, a link swapped in after the check, cannot be pinned.
  - **A timeout's wording.** It needs a git that outlasts the history question's 60 s, and the question has
    no seam for a shorter one. `GitProcessTest` pins the timeout itself. Without the wording the reason
    still gives the exit code.
  - **An empty first line.** Taken for git's reason, it would leave a reason that ends in `: `, or lose git's
    words after a leading blank line. No git message that starts with one is known here, and none was looked
    for.
- **Comments** (`fc57595`). The KDocs of `TrackedStateEntries` and the `TrackedState` port say the history
  question reads the reflogs too, and that "ever" goes as far as git remembers.
- **Gates**, all exit 0, at `fc57595`:
  - check;
  - test: 2,329 JUnit tests in 166 classes, 0 failures, 9 skipped as before, and node 4 of 4;
  - build;
  - `verifyBranchCoverage`: `adapter/store` 85% (687 of 806), `adapter/git` 87% (320 of 365), `adapter/mcp` 92%
    (340 of 368), every package at or above its floor;
  - guards: 12 of 12, run again with this page and progress staged.
- **What remains.**
  - Frames held in memory while `.ps` is refused are lost if the server stops. The first WARN says so.
  - A forged session or orphans file that the owner untracked without deleting is replayed or read once git's
    history stops naming it: an expired reflog entry, a rewritten history, a shallow fetch. The TRACKED
    reason and `SECURITY.md` make the delete mandatory, and nothing enforces it.
  - A git that cannot say what it has ever tracked holds every replay and every orphans read. The WARN now
    says why.
  - F5's race.
  - Not measured in the image; CI has not run this branch; not verified live.

**PR #401: CI, and the critic's check at `dd9648b`.** The coordinator rebased the round onto main `258ed10` before
pushing it, so its commits above carry the names the push gave them. What follows is on top of `dd9648b`, with no
rebase.

- **CI failed on all three OSes** (`548520a`). The test of the reason's 200-character cut gave git a `.git` file
  naming a missing directory. Git 2.48.1 here, and 2.53.0 on Ubuntu 26.04 in Docker, name that directory in full;
  CI's gits printed `fatal: not a git repository: (null)`, and `(NULL)` on Windows, so the cut was never reached.
  The reason is now built by `TrackedStateEntries.reasonOf` from git's result alone, and pinned there with results
  made in the test: the cut, the first line that says something, a git that said nothing, and one that timed out.
  The last two were among the four the mutation check above left. The one real-git test keeps only what every git
  says, `git log exited 128: fatal: not a git repository`.
- **CI's Windows leg failed the two new FIFO tests** (`24ae432`, `a1578d2`). Both assumed `madeFifo()`, which
  trusted `mkfifo`'s exit code. The runner's `mkfifo` is Git for Windows', which exits 0 and leaves a file the JVM
  reads as a regular one; #398 met it in `WatchTokenTest`. `madeFifo()` now also requires the JVM to read what was
  made as "other", in the same text #387 carries, and both tests assume `canPlantLinksIn()` first, as the other FIFO
  tests do.
- **Medium, measured: a release lost what it could not write** (`8bb62c1`). The critic swapped a link in just after
  the release's check. In 400 runs the next grading's first `append()` threw 164 times, through `decided()` and
  `releaseHeld()`, and 376 of 400 held frames were lost: they had left memory before the write. Nothing was written
  through the link. `ChannelCapture.onFrame` does not catch the throw, so the observation flow would fail and
  reconnect (inferred).
  - A release now takes a run's or a lesson's frames, writes them, and on a failure puts them back ahead of anything
    held since. It never throws, so a live grading never fails over a held frame.
  - An orphan's own write that fails is held as a refused one is.
  - The failure is said once, by the exception's kind and never with a path.
  - An orphan is held inside the map's own step for its lesson, so a release can no longer take a list that a frame
    is then added to.
  - Three tests close a directory to writes, which fails the append as any failure would. Each failed before with
    `AccessDeniedException` thrown out of `append()` or `orphaned()`.
- **Low, inferred: the link check came before git's history question** (`6960164`). `orphans()` checked `orphans/`,
  listed it, asked git, and then read. A pull in between could swap in a link, and the files were read through it,
  since a no-follow open covers the last component alone. Once git has answered, the directory is now checked again
  and listed anew. The new test swaps the link in during the question; before, the call counted the target's 40
  lines as orphaned frames.
- **Accepted, not fixed: a file swapped for a FIFO between the check and the open** hangs `orphans()` for good. Git
  cannot make a FIFO, so it takes a local process with write access to `.ps`.
- **Merged** main `40bc5f5` (#387, PR #398) as `b0dea9e`. Its `withdraw` and read-time checks sit beside this
  branch's orphans guard, settled share and release fix. Three conflicts, each kept both sides.
- **Tests.** Eight new: four at the reason's seam, three for the release, one for the swap. The bound test that read
  git's wording is gone, and the real-git reason test now asserts the stable start only.
- **Mutation**, against the store, git-history, MCP, application and config tests:

  | Mutant | Tests failed |
  |---|---|
  | the timeout not named | 1 |
  | git's line not cut | 1 |
  | an empty first line taken for git's reason | 2 |
  | git's line not trimmed | 1 |
  | no put-back for a lesson's orphans | 2 |
  | no put-back for a run | 1 |
  | no catch, so a failed write is thrown at the capture | 3 |
  | an orphan's own failed write not held | 1 |
  | the failure said at every write | 1 |
  | a lesson's orphans, or a run, put back after what was held since | none |
  | no check after git's history | 1 |
  | a refused re-check taken for no orphans | 1 |

  The pair left takes a frame held while a write is in flight, a concurrency the tests do not drive. Of the four
  the earlier check left, the orphans reader's two link checks remain.
- **Linux.** The changed test classes ran on Ubuntu 26.04 with git 2.53.0, as a non-root user, in
  `eclipse-temurin:25-jdk`: 353 tests, 0 failed, 4 skipped (a Windows junction, and three that need a filesystem
  that folds case).
- **Gates**, all exit 0, at `6960164`:
  - check;
  - test: 2,445 JUnit tests in 169 classes, 0 failures, 11 skipped (the 9 before, and #387's two Windows
    junction tests), and node 4 of 4;
  - build;
  - `verifyBranchCoverage`: `adapter/store` 86% (716 of 832), `adapter/git` 90% (360 of 400), `adapter/mcp` 92%
    (340 of 368), every package at or above its floor;
  - guards: 12 of 12, with this page and progress staged.
- **What remains**, besides the list above: the FIFO swap; and CI has not run these commits.

**The last rebuild, 2026-10-10 at 23:41 KST.** Main `794c37f` carries every PR from #355 to #411, all of
this page's follow-ups among them (raw/sessions/2026-10-10-the-limit-the-load-and-the-last-five.md).

- **Measured.** The boot was healthy, with no WARN or ERROR line. Its reconcile committed `b1e116e`,
  "chore: reconcile uncommitted records": a five-line change to `.gitignore`, #386's seeded rule for the
  temporary files, and nothing else. The records repository was then one commit ahead of origin, to stay
  so until the next backup push.
- **Inferred.** This was the commit half of the gate's first pass over a real reconcile in the owner's
  repository since #360 shipped: both earlier checks found HEAD at `7e144fa`.
- **Still not exercised:** the push half, on the owner's repository.
