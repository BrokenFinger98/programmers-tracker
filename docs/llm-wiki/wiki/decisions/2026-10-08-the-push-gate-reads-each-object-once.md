---
type: decision
project: programmers-tracker
tags: [security, git, credentials, push, performance]
author: BrokenFinger98
created: 2026-10-08
updated: 2026-10-08
sources: [decisions/2026-10-08-reconcile-never-stages-the-state-directory]
---

# The push gate reads each object a push would send once

## Context

The push half of #360's content gate ([[decisions/2026-10-08-reconcile-never-stages-the-state-directory]],
layer 4) ran `git grep` over every outgoing commit, 256 commits to a call, once for GitHub's token shapes
and once for the stored values. `git grep <commit>` reads that commit's whole tree, so a first push read
every unchanged file once per commit: the work grows with commits × tree size.

#373 asked for the cost to stop growing that way. Measured here (Apple M4 Pro, git 2.48.1) on synthetic
histories shaped like the #360 review's — each commit adds one file of base64 text, so nothing is
token-shaped and every search runs to the end — through `push()` to a remote that has a URL and is not
there, so the gate runs in full and the push fails at once. The slowest single call is from the same
`git grep` calls replayed in a shell:

| History | Tree | First push on main | Slowest single call |
|---|---|---|---|
| 1,660 commits | 4.2 MB | 25.2 s | 3.3 s |
| 5,000 commits | 9.8 MB | 252.5 s | 16.1 s |

Each call ran under `GitProcess`'s 60-second timeout, so a larger tree times out on every attempt and the
push is refused for good; and a range only shrinks when a push succeeds, so a first push that failed ran
the whole search again on every pass.

Two blind spots of `git grep` came up in #372's review while this was open. In a UTF-8 locale on macOS,
`git grep -E` misses a token beside a byte that is not valid UTF-8 and exits 1 with nothing on stderr
(measured again here: of five files with a token beside `0xE9`, `0xC0 0xAF` or `0x80`, `en_US.UTF-8`
found none and `C` found all five); #372 pins `LC_ALL=C` for every git call. And no locale finds a token
in UTF-16 text, which Windows PowerShell 5.1 writes with every `>`.

## Options considered

1. **Keep `git grep`, in bigger or parallel batches.** Each unchanged file is still read once per
   commit; parallel calls spread the same work over more cores. Rejected.
2. **Search each commit's diff (`git log -p`).** Reads only what changed, but a binary change comes as a
   base85 delta or not at all, and it is not the content a push sends. Rejected.
3. **`pack-objects --revs --stdout`, and parse the pack.** Exactly what a push sends, but the JVM would
   have to resolve deltas: a pack reader written for a search. Rejected.
4. **`rev-list --objects` → `cat-file --batch-check` → `cat-file --batch`, matched in the JVM.** Git lists
   each object once and inflates it; the JVM only matches. Chosen; the issue named it.

Within the chosen option:

- **How the blobs are picked out.** `rev-list --filter=object:type=blob` (git 2.32 and later) lists blobs
  alone, but it still prints the commit it was given unless `--filter-provided-objects` is added (measured),
  it gives no sizes, and commit and tag messages (#375) would need a second listing. `cat-file
  --batch-check` describes every listed object — type and size — on any git, and #375's types join one
  set. Its cost is small: on the 5,000-commit history it took 0.05 s for 25,000 objects. Reading every
  listed object instead was never an option: the trees there add up to 400 MB, against 9.8 MB of blobs,
  because the `problems` tree gains an entry with every commit.
- **How content is matched.** Decoded as UTF-8, a byte that is not UTF-8 becomes U+FFFD, a window seam
  can split a character, and characters stop counting bytes. Read as ISO-8859-1, each byte is the one
  character of the same number: nothing is dropped or merged, a window is bytes, and a match is found
  where `git grep` finds one in the C locale, in a binary blob as well. The shapes are `java.util.regex`
  patterns written as `git grep -E` reads them, and two parity tests run `git grep -E` and `git grep -F`
  in the C locale over the same probe files — each length bound, every prefix, NUL, newline, CR and
  non-ASCII bytes inside a run and beside it — and require the same files found. The stored values are
  matched as the UTF-8 bytes `git grep -F -f -` is fed, split into lines as it splits them (a CR before a
  newline is not part of a value).
- **How memory is bounded.** Streaming, and calls of bounded size, both. `GitProcess.runReading` writes
  stdout to a temporary file as `run` does, and hands it over as a stream once git has exited 0 in time.
  `BatchOutput` reads each object's content a window of 1 MB at a time; each window repeats the last
  `TokenPatterns.overlap` bytes of the one before. Objects go to `cat-file --batch` about 64 MB of
  content to a call, which bounds each call's time and its temporary file.
- **What a window must repeat, exactly.** `{36,}` and `{60,}` match wherever their shortest forms do, so
  a match exists if and only if a shortest match exists: 40 characters for a classic token, 71 for a
  fine-grained one, and a stored value's own length. A shortest match of m bytes that ends in a window
  begins at most m − 1 bytes before it, so a window that repeats m − 1 bytes holds it whole. The overlap
  is the largest such m, less one: 141 with no long stored value, since UTF-16 (below) doubles 71.
- **UTF-16, raised by #372's review.** A window that holds a NUL byte is also decoded as UTF-16, in both
  byte orders and from its first and its second byte, and searched for the shapes and for the stored
  values as text. UTF-16 text with an ASCII character in it always holds a NUL byte, so text windows are
  not decoded at all. Odd and even offsets are both read, so UTF-16 text that starts an odd number of
  bytes into a blob is found too.
- **Writing a call's input.** The scan hands `cat-file` every id on stdin. `GitProcess` wrote input on
  the caller's thread before the wait began, so a git that stopped reading held the caller past the
  timeout: 10.1 s against a 1-second timeout in the new test, after which git exited 0 and the call was
  reported as a success. Input is now written from a thread of its own, and a git past its timeout is
  killed through its `ProcessHandle`: `Process.destroyForcibly` also closes stdin, which waits for a write
  in progress — 10.0 s while a child of git held the pipe.

## Decision

The push searches what `OutgoingObjectScan` reads:

1. `git rev-list --objects <range>` names every object the range reaches, each once. The range is
   unchanged — `HEAD --not --remotes=<remote>` — and is decided in `CommandLineGitSync.outgoingRange`
   alone, for #376 and #378 to change. A second listing, `rev-list --objects HEAD^{tree}`, names HEAD's
   own tree at every push, whatever the remote-tracking refs say (added after the review of 315f44e, see
   the Outcome); an object both name is read once. *⚠️ Superseded by
   [[decisions/2026-10-08-each-gate-searches-what-its-destination-lacks]] (#376): the range leaves out what
   the push's destinations say they hold, through `ls-remote`, and HEAD's tree is no longer listed.*
2. `git cat-file --batch-check --buffer` describes each listed object, 50,000 ids to a call. Only blobs
   are read; commit and tag messages are #375's, and their types would join `READ_TYPES`. *Since #375,
   commits and trees are read too, and the push sends no tag — see the Outcome.*
3. `git cat-file --batch --buffer` prints the blobs, the objects whose content starts in the same 64 MB
   of the whole going to one call (and no more than 50,000 of them). `BatchOutput` reads each call's
   output against what was asked and searches it in windows (`TokenPatterns`).
4. Fails closed, each as `CREDENTIAL_UNSEARCHED`: a listing, a description or a read that fails or does
   not finish in time; a description that is not every id asked for, in order, or that names an object
   `missing`; a header other than the one asked for — another id, type or size, or `missing`; content
   that ends early; a missing newline after it; output left over; a header longer than any header. A
   match is `CREDENTIAL_FOUND`; a store that cannot be read is `CREDENTIAL_UNREADABLE`, as before. The
   WARNs are #360's, and name neither what matched nor where.

The commit side's two searches (`--untracked`, `--cached`) stay `git grep`. `lastSearchedClean` keeps its
key. The token shapes move to `TokenPatterns.SHAPES`, which the commit side's grep also reads. *Since #376
the commit side reads with this scan too, and no `git grep` is left; since #378 the key covers what was
searched.*

The git this relies on: `rev-list --objects`, and `cat-file --batch` and `--batch-check` in their default
formats, far older than any git the tracker runs with; and `cat-file --buffer`, which came with
`--batch-all-objects` in git 2.6 (2015). Nothing needs 2.32's object filters. Each behaviour a refusal
rests on was measured on 2.48.1 here, and with the same results on 2.53.0 in the tracker's image (Ubuntu
26.04): a missing loose blob fails `rev-list --objects` (128); a loose object that will not inflate is
listed, then described `missing` (exit 0); half of one is described, then fails `--batch` (128).

## Rationale

Measured on the same histories, the same way: main once per first push, the rest three to eight runs. The
middle column is the range alone; the last adds HEAD's tree, as the branch now does:

| | main | the range alone | and HEAD's tree |
|---|---|---|---|
| First push, 1,660 commits, 4.2 MB tree | 25.2 s | 0.21–0.33 s | 0.21–0.28 s |
| First push, 5,000 commits, 9.8 MB tree | 252.5 s | 0.52–0.63 s | 0.50–0.59 s |
| First push, log history (below) | 56.3 s | 4.3–7.2 s | 3.9–9.5 s |
| Five outgoing commits, 1,660 / 5,000 / log | 0.22–0.31 / 0.48–0.54 / 0.31–0.33 s | 0.12–0.18 / 0.13–0.16 / 0.16–0.37 s | 0.17–0.25 / 0.24–0.29 / 0.19–0.21 s |
| Nothing outgoing, 1,660 / 5,000 / log | 0.07–0.13 s | 0.09–0.14 s | 0.21–0.37 / 0.31–0.40 / 0.20–0.46 s |

The slowest single call on a first push, with every call timed: 0.14 s on the 1,660-commit history,
0.38 s (`rev-list --objects`) on the 5,000-commit one, and 1.03 s on the log history — one 64 MB
`cat-file --batch` call, the JVM's matching of its output included. On main, replayed in a shell, they
were 3.3 s, 16.1 s and 6.9 s.

The log history is the tracker's own shape: each of its 1,660 commits also appends a line to
`log/submissions.jsonl`, as a submit does, so every commit carries a new version of a growing file. Its
tree is 3.4 MB; its blobs add up to 729 MB, every version of the log read whole. That is where the
speed-up is smallest (8–13 times), and it is still never more work than `git grep`, which read the same
versions as part of every tree.

Each object is read once because `rev-list --objects` prints an object the first time it reaches it, and
a blob many commits share is one object. A test counts the ids each `cat-file --batch` was asked for: a
blob in 14 commits, and the same content at two paths, is asked for once, and only blobs are asked for.

The UTF-16 view and the overlap are pinned by tests that fail without them, below. A token beside an
invalid UTF-8 sequence is pinned at three levels — the parity probes, real blobs read by the scan, and
the bytes `BatchOutput` reads.

## Accepted costs

- **A file that changes in every commit is read in full at every version.** On the log history that was
  729 MB and 3.9–9.5 s on a first push; it grows with the sum of the file's versions, not with the
  tree. Each call stays bounded — 64 MB of content, at most 1.03 s measured — but the total does not.
  Reading only the bytes a version added would need diffs, which option 2 ruled out.
- **A call's output waits on disk.** About 64 MB at a time, and a single blob larger than that is one call
  of its own size. The temporary file is deleted when the call is read.
- **The list of objects is held in memory.** `rev-list`'s answer and each description are read whole:
  an id line per object, about 25,000 lines on the 5,000-commit history. Content never is.
- **HEAD's tree is read at every push.** Its blobs — about 431 KB on the owner's repository, 3.4–9.8 MB on
  these histories — are read even when the push sends none of them: about +0.05–0.1 s with five commits
  outgoing, +0.1–0.25 s with nothing else outgoing, measured above. So a token in a file still in HEAD's
  tree refuses every push until a commit removes it, as #360's search did. What the remote holds only in
  older commits is not read again — the push does not send it, and it was public from its first push.
  *Gone with #376: HEAD's tree is not listed, and a string the remote already holds no longer refuses.*
- **The push now finds what a commit does not.** A token in UTF-16 text is refused at the push, while the
  commit side's `git grep` still lets it into a local commit, so every push is refused until the token is
  removed from history. Fail closed, and out of this issue's scope (the commit side stays `git grep`).
  *Resolved by #376: the commit side reads what it adds with this scan, UTF-16 included.*
- **Other encodings are not read.** UTF-32, base64, a compressed or encrypted blob: a token in one is
  missed, as `git grep` missed it. A UTF-16 stored value made only of characters with no zero byte in
  either byte order would be missed in a window without a NUL; a GitHub token and the stored line are
  ASCII.
- **The UTF-16 view can find more than is there.** In a window that holds a NUL, any two bytes can spell
  a UTF-16 character, so a stored value that is not ASCII can be found where its code units merely
  stand side by side, and the push is refused where `git grep` would not have refused it. The shapes are
  ASCII, and an ASCII character read as UTF-16 needs its zero byte, so they are not affected.
- **Parity rests on probes.** The shapes are compared with `git grep -E` on the probes the test holds, in
  the C locale #372 pins. A git whose regular expressions changed elsewhere would drift unseen.
- **A stale remote-tracking ref still narrows the range** (#376, #378). The tracker never fetches, so a ref
  stays where the last push left it; after `remote set-url`, or with the remote re-created empty, it can
  name commits the remote does not hold. What is covered: HEAD's own tree, read at every push whatever the
  refs say — what `git grep` read of HEAD before #373. What is not: a commit behind the stale ref, one the
  ref reaches and the remote lacks, and whatever only it carries. A token in such a commit's tree that
  HEAD's tree no longer holds goes out unsearched. `git grep` did not read it either: it searched only
  the commits the same range named. *Resolved by #376: the range is what the destinations say they hold.*
- **Commit and tag messages are still not read** (#375). *Resolved by #375, in the Outcome: commits and
  names are read, and no tag is sent.*

## Outcome

#373 on `perf/373-scan-new-objects`:

- `8493d88` input written from its own thread, and a git past its timeout killed through its handle;
- `43a111c` `GitProcess.runReading`;
- `983b8b6` `TokenPatterns`, with the shapes moved there, the UTF-16 view and the parity tests;
- `8da5a08` `GitObject` and `BatchOutput`;
- `58de882` `OutgoingObjectScan`;
- `9a79453` the push gate on the scan, and three push tests;
- `53c6598` the CR, exit-code and every-id checks pinned;
- `37bb284` both UTF-16 byte orders and the NUL gate pinned;
- this page, the #360 page's superseded lines, the index and progress.

Every new test was red first. Each new class against a stub that answered clean or read everything
whole: 15 of the first 16 `TokenPatterns` tests, then 8 of them against a version that read bytes alone
(the UTF-16 and overlap ones); 10 of 11 `GitObject` tests; 8 of 19 `BatchOutput` tests, the stub reading
the endless header to an `OutOfMemoryError`; 17 of 20 scan tests. `a token in a UTF-16 file is never
pushed` against the push still on `git grep`: the push went out (`expected:<false> but was:<true>`). The
`GitProcess` test against each half of its fix: 10.1 s and success with the input on the caller's thread,
10.0 s with the old kill.

36 mutants, each a behaviour removed, run against the final code. 34 fail a test. One more fails the
build: a header read without a bound exhausts the test JVM's heap (`Java heap space`) on the test written
for it. One is equivalent: content that ends early taken for the end of the object, since a short read
happens only at the end of the output, where the newline that must follow fails as well. Planning the
run found three checks no test could fail — a CR kept in a stored value, and the two batch-check checks,
each of which covered the other — pinned in `53c6598` before it ran. The run left two survivors besides
the equivalent one, UTF-16LE alone and the NUL gate gone, pinned in `37bb284` and run again. The
mutants, with how many tests each failed: input on the caller's thread 1; the old
kill 1; `runReading` reading whatever the exit 2; no UTF-16 view 9; UTF-16 from the first byte only 1;
one byte order only 1; the UTF-16 view without the stored values 1; every window read as UTF-16 1; stored
values as characters 2; the CR kept 1; empty lines kept 6; the overlap without UTF-16 3, without the
stored values 2, a byte short 3, gone 4; the tail carried into the next object 1; the header not compared
2; no newline after the content 1; output left over accepted 1; any type accepted 2; a negative size 1; a
failed `rev-list` taken for nothing outgoing 4; a failed description accepted 1; a description short of
an id accepted 1; a failed or timed-out read taken for clean 2; only the first call read 1; trees and
commits read 1; every commit's tree read, as `git grep` did 5; the range ignored 3; every object in one
call 1; the push on `git grep` again 2; the range from every remote 1; `UNSEARCHED` let through 3;
`FOUND` said as unsearched 5.

Gates, all exit 0: check; test (2,129 JUnit across 164 classes, 0 failures, 9 skipped — 8 C# and the
`icase` test on this case-insensitive host; node 4/4); build; `verifyBranchCoverage` (`adapter/git` 88%,
306 of 345, from 85%, 225 of 262; `adapter/config` 65% at its floor); guards.

Not verified live, and not run on CI: Windows skips the four new tests that run a shell alias. The branch
was rebased onto main `cb78438`, which carries #372's `LC_ALL=C` pin in `GitProcess`; this branch changed
neither that nor `isDirty`.

**The review of 315f44e found one regression** (Medium). The search before #373 read the whole tree of
every outgoing commit, HEAD's included; the scan read only what the range reaches. The tracker never
fetches, so after `remote set-url` to a new remote, or with the remote re-created empty, `origin/main`
still named a commit `T`, pushed by another tool, whose tree held a token in `leak.md`. A clean commit
`Y` on top still held the file, and the push of `Y` went out with it: true, and the token on the new
remote, in both scenarios on 315f44e; refused on main.

`eaab208` lists HEAD's tree beside the range at every push (Decision 1). Two tests run the reviewer's
scenarios with real remotes — the push is refused and the new remote holds no ref — and both were red on
315f44e (`expected:<false> but was:<true>`). Three scan tests: what a second listing alone names is read,
what both name is read once, a second listing that fails refuses. Against a stub that read the first
listing alone, two of them failed; against one that did not dedupe, the third (`expected:<1> but was:<2>`).
Four mutants, each killed: HEAD's tree not listed (2 tests), no dedupe (1), a failed listing taken for an
empty one (5), the first listing alone (4). The cost is in the Rationale's last column and in the costs
above; what a stale ref still leaves out is #376's.

Gates on that commit, all exit 0: check; test (2,246 JUnit across 166 classes, 0 failures, 9 skipped;
node 4/4); build; `verifyBranchCoverage` (`adapter/git` 88%, 311 of 353; `adapter/config` 65% at its
floor); guards. Two coverage runs failed first, in the one `@SpringBootTest`: it records into a fixed path
under the system temp directory, and another worktree's test run held its lock
(`RecordRepositoryLockedException`). The run that waited for that worktree to go idle passed.

**#375 put commits and trees through the same reader, and stopped tags from going at all** (branch
`fix/375-scan-messages`). N6 in #360's review: a token in a commit message, an author or a committer was
pushed, since only files were read; and a token in a file or directory name was invisible to every search,
`git grep` included.

- **Commits.** `cat-file --batch` prints the commits the scan already lists, and the same `TokenPatterns`
  read them, the UTF-16 view included. A commit's header, up to the empty line that ends it, is searched
  apart from its message, so the refusal names the commit by a 12-character id and the part — "the
  message", or "the author, committer or another header line" — and never the token. No match spans that
  empty line, as neither a shape nor a stored value holds a newline; the line can fall across a window's
  seam, and a test puts it there. A tag's text merged into a commit, a `mergetag` header, is read with it.
- **Trees, as bytes alone.** A tree holds the names, so a match in one is said as "a file or directory
  name", never which. Its object ids cannot make a false match: a run of token characters touching an
  id ends at the NUL before it and the space after the next entry's mode, 26 bytes at most against the 40
  a token needs. Measured on the 5,000-commit history: the longest such run in 12,512,500 entries was 10
  bytes, and no window of its 15,000 trees (382 MB) matched. The UTF-16 view is left off for trees: a
  name cannot hold a NUL, and UTF-16 text of an ASCII character always does; every tree holds NULs, and
  the view cost 6.2–7.1 s on those trees against 1.0–2.0 s for the bytes alone.
- **Tags: never sent, rather than read.** The push names one branch, yet with `push.followTags=true` in
  the records repository git sent every annotated tag on it too, its message never read: measured, a tag
  on HEAD whose message held a token reached the remote as `refs/tags/v1`. The push now says
  `--no-follow-tags`, so no tag goes, whatever the configuration says, and none needs reading.

Measured as before (whole `push()`, three to five runs):

| | main | before #375 | #375 |
|---|---|---|---|
| First push, 1,660 / 5,000 commits / log | 25.2 / 252.5 / 56.3 s | 0.21–0.28 / 0.50–0.59 / 3.9–9.5 s | 0.44–0.54 / 1.82–2.40 / 4.7–6.9 s |
| Five outgoing commits | 0.22–0.31 / 0.48–0.54 / 0.31–0.33 s | 0.17–0.25 / 0.24–0.29 / 0.19–0.21 s | 0.14–0.22 / 0.22–0.26 / 0.17–0.19 s |
| Nothing outgoing | 0.07–0.13 s | 0.20–0.46 s | 0.15–0.27 s |

The trees are what a first push pays for: 44 / 401 / 44 MB of them, against 4 / 10 / 729 MB of blobs. The
slowest single call on a first push was 0.21 / 0.28 / 0.33 s. Five outgoing commits add a few trees and
commits, and cost nothing measurable.

Accepted with it:

- **The commit side still reads file content alone.** A file named with a token is committed by a
  reconciliation, and then every push is refused until the history no longer holds the name. Fail closed;
  the owner rewrites history, as for any token in what is committed. *Resolved by #376: the paths a
  commit adds are matched as their bytes, and such a commit is refused.*
- **The header is one part.** The author, the committer and a merged tag are not told apart; the commit's
  id is what the owner needs, and `git cat-file commit <id>` shows the rest.
- **A UTF-16 name is read as bytes.** A stored value whose UTF-16 form holds no zero byte would be missed
  in a name written in UTF-16; no tool writes names that way, and the shapes are ASCII.
- **The owner's own pushes are unchanged.** `--no-follow-tags` binds the tracker's push alone; a `git push`
  the owner runs still follows the repository's configuration, and is not gated.

Each new test was red first: 12 for commits against types with no behaviour (the pushes went out,
`expected:<false> but was:<true>`, and a commit hit read as a blob's); the names against a scan that read
no tree (`Clean`), the push of a token-named file going out, and the bytes-alone matcher finding the UTF-16
token; the tag test with the remote holding `refs/tags/v1`. A pin that was green from the start: an
object id of 20 letters does not complete a token.

17 mutants, each a #375 behaviour removed, all killed, with how many tests each failed: commits not read
7; trees not read 4; a commit searched whole, as a blob is 11; the header never ended 4; a commit read as
message from its first byte 5; the empty line across a seam unseen 2; the header without its tail 2; the
message without its tail 1; the header side of the window that ends it unsearched 5; a tree read with the
UTF-16 view 1; a tree searched as a blob 5; bytes alone read as UTF-16 too 2; tags followed again 1; the
commit named by its whole id 1; the part not said 1; a commit finding said as content 2; a name finding
said as content 1. Two of them, the tails, failed to compile as first written and were rerun once they did.

Gates, all exit 0: check; test (2,268 JUnit across 166 classes, 0 failures, 9 skipped; node 4/4); build;
`verifyBranchCoverage` (`adapter/git` 88%, 341 of 384; `adapter/config` 65% at its floor); guards.

**#376 and #378, after #375** (the #376 page, linked from Decision 1; branch
`fix/376-gate-range-and-scope`). The range this page left to #376 is now what the push's destinations say
they hold, through `ls-remote` on each push URL, and the cache is keyed on what was searched (#378). The
commit side reads with this scan as well — what a commit adds, found by `add` on a copy of the index —
and matches the paths it adds as bytes, which closes the two gaps this page accepted for the commit side:
UTF-16 text and names.

**HEAD's tree is no longer listed at a push.** It was the cover for a range that trusted stale refs, and
the range no longer does. It is redundant: the two tests the review of 315f44e left — `set-url` to a new
remote, the remote re-created empty — pass without it, since the new remote holds nothing and the range is
then all of HEAD's history; and what HEAD's tree holds and the destinations lack is in the range, while
what they hold is not sent. It was also contrary to #376's second finding: listed, it refused every push
for a token-shaped string the remote already held (`a push goes ahead when the remote already holds what
HEAD carries`, red with it). The Rationale's last column and the cost "HEAD's tree is read at every push"
are history now.
