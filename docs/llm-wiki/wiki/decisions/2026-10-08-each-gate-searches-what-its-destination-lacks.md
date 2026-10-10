---
type: decision
project: programmers-tracker
tags: [security, git, credentials, push, commit]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-08
sources: [decisions/2026-10-08-reconcile-never-stages-the-state-directory, decisions/2026-10-08-the-push-gate-reads-each-object-once]
---

# Each gate searches what its destination lacks

## Context

#360's content gate ([[decisions/2026-10-08-reconcile-never-stages-the-state-directory]], layer 4)
trusted the wrong thing on one side and searched too much on the other. Its final adversarial pass
found both (#376):

1. **The push range came from the remote-tracking refs** — `rev-list HEAD --not --remotes=<remote>`. The
   tracker never fetches, so those refs say where its last push left the remote, not what the remote
   holds. A remote re-created under the same name, or repointed with `set-url`, was vouched for by refs
   naming commits it never received, and a token pushed once went to it again, unsearched. #373 listed
   HEAD's own tree beside the range at every push
   ([[decisions/2026-10-08-the-push-gate-reads-each-object-once]]), which covered a token still in HEAD
   and not one in its history.
2. **The commit gate searched everything in its scope**, HEAD's content with the rest. A pull that brought
   in a file with a token-shaped string refused every reconciliation from then on, with "revoke the
   token", though a commit adds nothing of it and a push sends nothing of it that the remote lacks.

#378 added two. The clean-search cache was keyed on the head, the remote's name and the store, so a head
searched for one remote was sent to another unsearched once `origin` was repointed and fetched with
`--prune` — the gate critic's C1: a fresh server refused, the same server sent the token. And a URL given
as `branch.<b>.remote`, which git pushes to as it is, was skipped as a missing remote. Then #375's
implementer found that the commit side read content alone: a file named with a token was committed, and
every push after it refused.

## Options considered

**Where the range's tips come from.**

1. `git push --dry-run --porcelain`. It says which refs would move, not which objects go. Rejected.
2. `git ls-remote <remote>`. It asks the fetch URL: with `remote.<r>.pushurl` set apart, it listed what
   another repository held (measured). Rejected.
3. **`git ls-remote` on each push URL** (`git remote get-url --push --all`), keeping the tips this
   repository holds. Chosen.

**What a commit is searched for, and how it is found without staging it.**

1. `git grep` over the paths that changed. UTF-16 text and names stay unread, and "changed" would be
   `status`'s view rather than what `add` stages. Rejected.
2. `git hash-object -w --stdin-paths` over the changed files. It follows links (measured: a link to a file
   is hashed as that file, a link to a directory fails), so a link to `.ps/git-credentials` would be hashed
   as the token and written into the object store, where `add` stores the link's path. It applies no
   filter as `add` does either. Rejected.
3. **`add` on a copy of the index, compared with HEAD's tree.** What the commit would stage, by git's own
   rules, while the real index stays as it was. Chosen. A fresh index read from HEAD's tree instead of a
   copy has no record of the files' sizes and times, so `add` reads every file: 565 ms against 35 ms for
   one new file among 5,000 (measured, git 2.48.1). The copy keeps the index's own modification time,
   which is how git tells an entry it must not trust ("racily clean").

**Whether the search before staging can be "what the commit adds" too** — #360 kept it so a refused file
is never left staged (its review's M5). It has to be. Searched whole, it still refused every
reconciliation for the pulled string, and finding 2 stands. And it has to read what `add` stages, as the
push reads it: `git grep` finds no token in UTF-16 text, so one passed the search before staging, was
staged, and the search after staging refused with it left in the index — M5 broken again for UTF-16.
Run on a copy of the index, the search before staging is both.

**Whether the search after staging stays.** #360 searched the index again after `add`. The preview runs
the same `add` on the same index, so a second search reads the same blobs again. What it alone could catch
is a file that changes between the two `add`s — the same race as a file that changes between the real
`add` and the commit, which no search before the commit closes, since `git commit -- <paths>` reads the
working tree again. The push searches what was committed. Dropped.

**What HEAD already holds.** Refused, it is the false alarm that never ends. Left unsaid, a published
token is never revoked. **Said once, without what or where, and the commit goes ahead.** Chosen.

**HEAD's tree at every push (#373).** It was the cover for a range that trusted stale refs. With the
range from what the destinations say, it is redundant: what HEAD's tree holds and the destinations lack is
in the range, since `rev-list` leaves out only what an excluded tip reaches; and what they hold is not
sent. It is also contrary to finding 2: it refused every push for a string the remote already held, the
same false alarm one gate further. Removed rather than kept as defence in depth — a defence that refuses
every push once the remote holds a token-shaped string does not stay switched on.

## Decision

1. **The push range** is `HEAD --not <tips>` (`outgoingRange`, `RemoteTips`). The tips are what
   `ls-remote` lists at each of the remote's push URLs, kept where this repository holds them (one
   `cat-file --batch-check`), and only what every URL holds. A destination that cannot answer stops the
   push: nothing is sent, and one WARN says so until it answers again — never git's words, since a URL can
   carry a credential. Remote-tracking refs never decide the range.
2. **The cache of a clean search** is keyed on the head, the destinations, the tips the range left out,
   and a digest of the store (`SearchedHead`).
3. **A URL as a branch's remote.** A value that `remote get-url` does not know as a name and that holds a
   `/`, `\` or `:` — git's own test of a remote's nickname, and the scp form — is its own destination, for
   `ls-remote` as for the push. `hasRemote()` answers true for it, so #390's "no remote", which asks it,
   agrees with the push.
4. **HEAD's tree is not listed at a push.**
5. **The commit side** (`StagingPreview`, `Introduced`). Before anything is staged, the index is copied,
   with its time, into a directory of its own; `git add --all -- <scope>` runs on the copy
   (`GIT_INDEX_FILE`, for that call alone — `GitProcess.run` takes variables); and `git diff-index --cached
   -z --no-renames <base> -- <scope>` compares it with HEAD's tree, or the empty tree before the first
   commit. The paths it adds are matched as their bytes, never as UTF-16 — a path holds no NUL — and a
   match refuses as "a file or directory name" (#375's words). The blobs it leaves where something else
   was go to the scan #373 reads with, as `rev-list --objects <ids> --not <base>`, 500 ids to a call:
   whatever the base holds anywhere is left out, so content a commit moves is not added. Only then do the
   real `add` and the commit run. When git cannot stage the scope, the refusal is git's own words, as it
   was when the real `add` failed. No `git grep` is left in the gate.
6. **What HEAD already holds** is searched at each reconciliation, before anything else is done: what
   entered HEAD's tree since the tree searched last, as `git diff-tree -r -z --no-renames` names it,
   searched the same way. Found, it is one WARN — a leak to revoke — and the reconciliation goes on. The
   first reconciliation of a process searches the whole tree, and so does the first after the store
   changes; a search that did not run to the end is tried again next time.

## Rationale

**What `ls-remote` costs a push.** In a shell, 50 runs: 17.9 ms against a local bare repository by path,
22.7 ms by a `file://` URL. Through `push()` with nothing outgoing, 30 runs each, alternating, on a loaded
machine: 49–63 ms before, 95–133 ms after, so about +40–70 ms a push. A real remote adds a network round
trip, not measured here. Its output is never printed.

**What the commit side costs** (a repository of 5,000 files of 5 KB, one new file per reconciliation,
through `reconcile()`, git 2.48.1): 872 ms before (median, two `git grep` runs over the whole scope, before
and after staging), 316–373 ms after. Nothing to commit: 62 ms before, 61–90 ms after. The first
reconciliation of a process: 690 ms before, 1.45–2.08 s after, the whole tree searched once for what HEAD
already holds; 1.5–1.6 s again from a fresh instance in a warm JVM.

**Each test was red first**, against the code before it (Outcome). The two #373 tests for a stale ref —
`set-url` to a new remote, the remote re-created empty — pass without HEAD's tree: the new remote holds
nothing, so the range is all of HEAD's history.

## Accepted costs

- **Every push asks each destination what it holds**: +40–70 ms on this machine, a network round trip on
  GitHub, and no push at all while one cannot answer — said once.
- **A race between asking and pushing.** A remote that loses a ref between `ls-remote` and the push is
  sent what that ref reached, unsearched: content it held a moment before.
- **The first reconciliation of a process reads HEAD's whole tree**: 1.5–2.1 s on 25 MB in 5,000 files,
  about 431 KB on the owner's repository. After that, only what enters it.
- **More git calls for a commit**: the copy's `add` and `diff-index`, `rev-list` and the two `cat-file`
  calls, beside the real `add` and the commit — still a third of what two `git grep` runs over the whole
  scope cost on the repository above.
- **A refused preview leaves what `add` wrote**: the blobs, the token's among them, unreferenced in the
  object store until git prunes them — as the real `add` left them before.
- **A path is matched whole.** A file added under a directory whose name already holds a token is refused,
  though the name is HEAD's already; a file HEAD has, changed, is not. *Resolved by #402: each part is a name,
  and one HEAD holds is not new — see the Outcome.*
- **What changes between the preview and the commit is committed unsearched**, as before: the commit reads
  the working tree again. The push reads what was committed.
- **The notice is once per arrival, per process.** A token-shaped string that stays in HEAD is said again
  after a restart, once.
- **#375's push reads every name in a tree it sends.** A commit that rewrites a directory holding a
  token-named file the remote already has sends a new tree with that name in it, and the push is refused,
  though the name is published already (measured: a new file beside it, refused; one elsewhere, pushed).
  Reading only the names a tree adds against what the remote holds is #375's to change; a follow-up.
  *Resolved by #402, in the Outcome.*
- **The range's tips went on `rev-list`'s command line**, and past about 800 of them Windows' 32,767
  characters failed every push (#405, inferred by the gate critic). *Resolved by #405: they go on stdin.*

## Outcome

On `fix/376-gate-range-and-scope`, which took #373 and #375 from main by merges, never by a rebase:

- `4885b82` `RemoteTips`: what each destination holds, by `ls-remote`, kept where this repository holds it;
- `3a1b4b8` the range from it, the cache keyed on what was searched (#378 item 1), and one WARN for a
  destination that cannot answer;
- `b51d317` a URL as a branch's remote (#378 item 4);
- `e9fefcc` `GitProcess.run` takes variables for one call;
- `129da01` `StagingPreview` and `Introduced`;
- `e5e0db0` HEAD's tree no longer listed at a push;
- `e16ad46` the commit side on the preview and the scan, its new paths, and what HEAD already holds;
- this page, the notes in the #360 and #373 pages, `SECURITY.md`, the index and progress.

**Red first.** Each of the 21 new `CommandLineGitSyncTest` cases fails against main's `CommandLineGitSync`
at `31152de`, #373 and #375 squashed in. The push range: the remote re-created, `set-url`, a `pushurl`
apart, the head searched for another remote (the gate critic's C1) and for a remote that no longer holds
what it did — each `expected:<false> but was:<true>`, the token went out. The unanswering destination was
said twice and in git's words, and a URL remote was skipped. The commit side: the pulled string refused
(`expected:<true> but was:<false>`), and so was a move of it; a UTF-16 token, a token-named file and
directory, and a preview git cannot make all committed (`expected:<false> but was:<true>`); every notice
missing; and an unreadable file said as an unfinished search instead of in git's words. A push past what
the remote already held was refused while HEAD's tree was listed. `IntroducedTest` failed 9 of 13 and
`StagingPreviewTest` 9 of 10 against stubs that answered nothing introduced and never failed; the four the
stubs passed pin absences — nothing named for a removal, a change, a submodule, or a path with no token —
and mutants kill them below. The `GitProcess` test failed against `run()` ignoring its variables.

**Mutation**, each mutant reverted after its run. The 17 of the push side ran against `CommandLineGitSyncTest`
and `RemoteTipsTest`: 16 killed — the range from the remote-tracking refs, `ls-remote` of the remote's
name, the cache without the destinations and tips or without the tips, an unanswering destination taken
for one that holds nothing, the WARN never said again or said at every attempt, a URL never taken for one,
`hasRemote()` blind to it, the union of the destinations, tips this repository lacks kept, a line that is
no ref skipped, a description short of a tip, no destination taken for nothing held, a failed description
or `ls-remote` accepted. One is equivalent: the cache without the destinations, since the tips they hold
change with them. They first ran before #373 and #375 were merged in; the five beside the range and the
cache ran again on the final code, with the same verdicts — the range from the remote-tracking refs 7
tests, the cache on the head and store alone 2, without the tips 1, an unanswering destination taken for
one that holds nothing 2, and the cache without the destinations equivalent again.

The 32 of the commit side ran against `CommandLineGitSyncTest`, `IntroducedTest`, `StagingPreviewTest` and
`GitProcessTest`, with how many tests each failed: searched after staging, not before (M5) 3; no search 11;
names not searched 3; names searched for the shapes alone 1; a name said as content 2; blobs not searched
12; a failed preview without git's words 1; a failed preview let through 1, after the test of a required
filter that fails only on the copy was written for it; nothing searched before the first commit 9; the
leak never said 5; HEAD searched whole every time 1; said once per process 2; the store left out 1; a
search that could not run remembered 1; HEAD's tree listed again 1; what the base holds not left out 9;
changed paths taken for added names 3; a path searched as decoded characters 1; a submodule's commit read
1; links not introduced 2; an answer cut short accepted 1; a header that is not one skipped 1; unbounded
listings 1; staged into the real index 7; the copy stamped now 1; no copy 1; the copy left behind 1; a
failed `add` or comparison taken for nothing introduced 2 and 1; a call's own variables ignored 7. Two are
equivalent: names read as UTF-16 too, since a path holds no NUL and the view never opens; and the index
assumed at `.git/index`, which in a linked worktree finds none and starts the copy empty — every file read
again, the same answer.

**Gates**, all exit 0: check; test (2,369 JUnit across 169 classes, 0 failures, 9 skipped — 8 C# and the
`icase` test on this case-insensitive host; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 87%,
429 of 488; `adapter/config` 65% at its floor); guards (12 of 12).

**After #390**, merged from main at `c49e033` (PR #399). #390 made `hasRemote()` never throw and the push
silent with no remote at all, and the daily backup asks `hasRemote()` to tell that setup from a push that
failed, and `hasPushCredential()` whether a remote was wanted. The merge keeps both sides: one
`runCatching` around the named remotes and the URL remote. `088b0a2` pins the interplay with the real
adapter and the real backup: a branch whose remote is a URL is backed up there, and one whose URL leads
nowhere has the backup say it could not push, beside the adapter's own line. Both failed against main's
adapter at `258ed10` — `expected:<true> but was:<false>`, and two warnings expected where none was said:
the URL remote skipped as no remote, and the backup silent. The second failed as well against the merge
taking main's `hasRemote()` as it was, with the adapter's line alone. Mutants of the merge, each killed:
main's `hasRemote()` taken 2 tests; this branch's, unguarded 2; this branch's `noRemote()`, said with no
remote at all 3; the credential dropped 2. The range and cache mutants ran a third time, on the final code,
with the same verdicts: the range from the tracking refs 7, the cache on the head and store alone 2, without
the tips 1, an unanswering destination taken for one that holds nothing 3, a URL never taken for one 4, and
the cache without the destinations equivalent.

Gates on `088b0a2`, all exit 0: check; test (2,398 JUnit across 170 classes, 0 failures, 9 skipped; node
4/4); build; `verifyBranchCoverage` (`adapter/git` 88%, 436 of 492; `application` 89%; `adapter/config`
65% at its floor); guards (12 of 12).

Not verified: Windows, where the copy is named through `GIT_INDEX_FILE` with a Windows path, and where the
shell-filter and file-permission tests skip; CI has not run this branch; not live. The gate critic asked what a
temporary directory with a space in its path does there: the path goes in the environment, not on a command
line, and a copy git cannot use fails its `add`, which refuses the commit in git's words — fail closed. Not run.

**#402: a name the destination already holds is not new** (branch `fix/402-names-already-held`, after
#404). #375 read every tree a push sends for its names, and a tree carries every name in its directory, so a
file added beside a token-shaped name a pull brought in sent that name again, and every push of it was
refused — the false alarm this page removed for content, one gate further. The commit side had its twin:
the cost above, a file added under a pulled token-named directory refused at every reconciliation.

- **The rule, both sides:** a name that carries a token is new unless the destination's trees already hold
  an entry of that name — for a push, the trees of the tips its destinations said they hold (`ls-remote`,
  never a tracking ref); for a commit, HEAD's tree; for what entered HEAD, the tree searched before it. A held
  one goes out again; a push says it once for each name (by a digest), the commit side says it as it enters.
- **Held is the name, wherever it stands — not the tree at the same path,** as the issue first suggested.
  The leak is the string, and a string the destination publishes anywhere is not new by being sent again;
  `rev-list --objects` names a tree once, at the first path it reaches, so a tree used at two paths has no
  one path to compare; and a directory renamed whole would have sent every name as new. The cost: a token-
  shaped name copied to a second place goes out, said but not refused. A name held only in the remote's
  history, no tip's tree, is new, and refused.
- **How a tree is read:** entry by entry — mode, space, name, NUL, and an id skipped by its length (20 or 32
  bytes), whatever its bytes; each name matched whole, as bytes. Only a window that matches anywhere has its
  names read; a window that matches nowhere is walked for where its last entry ends, and the last window of a
  tree not even that, so a tree in one window that matches nowhere is never taken apart. What no entry ends
  at a tree's end is matched as bytes, as #375 read every tree; an entry past 64 KiB is not waited on, and
  refuses (no filesystem the records live on holds a name past 255 bytes).
- **What is held is listed only when a name matches**, and then once: one `cat-file --batch-check` peels
  the tips to their trees (a tip with none, a tag to a blob, holds none; measured), one `ls-tree -r -t -z
  --name-only` per tree lists the names as bytes. Git that cannot say is no answer, and refuses.

Cost, measured on the review's histories, the scan alone, before and after alternated twice (three runs
each, on a machine other agents were loading): everything, 0.23–0.27 s on 1,660 commits either way,
1.43–1.57 s against 1.43–1.57 s on 5,000, 3.47–3.70 s against 3.51–3.74 s on the log history (one run each
side ran past 4.9 s under load); five commits, 26–40 ms either way. No tree there is past one window, so
no tree that matches nowhere is taken apart. Listing what a tip holds, paid only when a name matches: 21–43
ms for the 10,001 entries of the 5,000-commit history.

Red first, against stubs that held nothing: 7 of 10 new `BatchOutput` tests, 8 of 11 `HeldNames`, 3 of 5
`Introduced`, both scan tests, and four pushes and a reconciliation, each `expected:<true> but was:<false>`.
31 mutants, each killed. Planning the run found three behaviours no test pinned — a tree of many windows
walked in step, a peel short of a tip, a name not said again — pinned in `962bd5a` before it ran; the run
left one, every name asked about, killed once its test read a tree in one window, where every name is in the
window that matches. With how many tests each failed: held names ignored 10; a name
that cannot be answered for taken as new 1, as held 1; a held name not handed over 3; every name asked about
1; ids taken as 20 bytes 1; the id not skipped 1; a name read with its NUL 11; what runs past a tree's last
entry let through 1; an entry waited on without end 1; what runs past a window dropped 10; a window that
matches nowhere not walked 1; the first tip alone 1; a tip with no tree as no answer 1; a failed peel as
nothing held 1; a failed listing as no names 1; whole paths held 9; listed at once 2; a peel short of a
tip accepted 1; the names sent again not kept 3; whole paths on the commit side 4; the base's names ignored
5; a name the base cannot answer for as held 1; every part asked about 43; the push without held names 3;
held names from the tracking ref 1; the notice at every push 1; never said 1; the commit's base holding
nothing 2; what entered HEAD against nothing 1; the scan not passing held names on 5.

**#405: a push goes where `ls-remote` asked, and the tips go on stdin** (two findings from the gate critic's
pass on #404, the same branch).

- **A chained `insteadOf` (measured by the critic).** `remote get-url --push` applies one `url.<base>`
  rule, and `ls-remote`, handed that URL, applied another: with B rewritten to A and A to C, C said what it
  held, the push went to A, and a token only C held landed on A. Each push URL must be what `ls-remote
  --get-url` makes of it (rule-free paths, `file://`, https and scp forms come back unchanged, measured), or
  nothing is pushed, said once until the rules agree. A branch whose remote is a URL goes to both as it is,
  each rewriting it once, so only a `pushInsteadOf` rule that starts it can part them: one does, and refuses.
  The check is `PushRewrites`. Its first form, in `d6d5fe7`, failed open twice, both found while pinning the
  critic's survivor (a rule listing that fails): a listing cut off by its timeout read as "no rule", and a
  rule whose base holds a space misread — `config --get-regexp` puts a space between key and value as well
  (measured) — so the rules are read with `--null`, and any listing but a match or "no key" refuses.
- **The Windows command line (inferred by the critic).** The range put every held tip in `rev-list`'s argv,
  and about 800 fill 32,767 characters. Every listing now goes on stdin, `--not` and all; measured here, 30,000
  tips failed to start on argv (exit 126, past macOS's 1 MB) and ran on stdin. A test pushes past 30,000.

Cost: `ls-remote --get-url` 6.7 ms a push URL, the rule listing 7.8 ms for a URL remote, each push.

Red first: the critic's chain, a URL remote a `pushInsteadOf` rule rewrites, and the notice said again, each
pushed where it must not (`expected:<false> but was:<true>`); the push past 30,000 tips (`could not run
(IOException)`); the listing on argv; and `PushRewrites` against a stub (6 of 8), then against `d6d5fe7`'s
logic (the base with a space and the timed-out listing). The first run of seven mutants, on `14d4a4a`, killed
six and left a rule listing that fails; pinning it found the two above. On the final code, eleven, each
killed, with how many tests each failed: the second rewrite not looked for 5; a `pushInsteadOf` rule on a
URL remote not looked for 5; a URL remote held to a named remote's check 7; a failed listing as no rule 2;
a listing cut off as no rule, `d6d5fe7`'s check 1; rules read without `--null`, `d6d5fe7`'s parsing 3; a URL
`ls-remote` cannot resolve taken as agreeing 1; the notice at every attempt 1; never said again 1; the check
skipped 3; the listing on argv again 2.

Gates for #402 and #405, on the branch with main `4700074` merged in, all exit 0: check; test (2,607 JUnit
across 176 classes, 0 failures, 12 skipped — 8 C#, three Windows-only store tests main brought, and the `icase`
test on this case-insensitive host; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 89%, 520 of 578;
`adapter/config` 65% at its floor); guards (12 of 12). Not verified on Windows, by CI, or live.
