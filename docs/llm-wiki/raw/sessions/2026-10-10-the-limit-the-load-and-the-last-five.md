# 2026-10-09 / 2026-10-10 — the limit, the load, and the last five PRs

Raw session record. Immutable (wiki schema §1). 2026-10-09 00:16 and 2026-10-10 16:11–23:41 KST, session
`b240e44e`, sliced by KST from the session transcript. Continues
`2026-10-08-seventeen-prs-through-one-queue.md`.

- **To 22:20:** the transcript snapshot.
- **22:20–23:40:** the two PRs that merged after it (#411, #410), from `progress.md` and the squash
  messages on main.
- **23:41:** the final live check, from the coordinator's pending notes.

---

## The wait (10-09 00:16)

The weekly usage limit had been reached at 23:51 on 10-08. At 00:16 the owner ran `/rate-limit-options`.
Claude Code answered that it would continue on its own at 16:00 on 10-10, and nothing ran in between.

## The resume (16:11–16:54)

The session resumed at 16:11. Before touching anything, the coordinator checked CI and every worktree's
state. Three agents had been cut off mid-task:

- #374's, with a rebase in progress;
- #402/#405's, partway through #405;
- #408's critic, before it began.

PR #406 (#386) had been sent to merge in the background, and the call had come back with nothing that
parsed. At 16:37 the PR was still open. A second call merged it as `4700074`. #374's and #402/#405's
agents were then resumed by message, each told where it had stopped and what had moved on main since.

## The load (17:20–21:24)

From about 17:20 everything ran slowly. What was measured, in order:

| Time | What happened | Load average (1 min) |
|---|---|---|
| 17:54 | #374's agent failed: no progress for 600 s, the stream watchdog. The coordinator's own #403 test run was killed at its 30-minute background limit. | 64.43 |
| 18:21 | #402/#405's agent failed the same way. | 78.10 |
| 19:37 | #403's 1,272 tests passed. The push took over 3 minutes and was moved to the background. | |
| 20:12 | The API reported that the computer had gone to sleep mid-response. The push was killed at its 30-minute limit. | |
| 20:23 | #374's agent failed again. `api.github.com` hung for 647 s at the same regional address as on 10-08, while `github.com` answered in 0.17 s. | 76.14–81.80 |

At 20:39 the cause was found: macOS's `XprotectService` was running at 773% CPU, alongside 33
`mdworker_shared` processes. Every process spawn (git, Gradle, curl) took minutes, so an agent's tool call
could return nothing for longer than the watchdog allows. Nothing in the repository caused it.

The coordinator waited rather than retried. A background monitor watched until the 1-minute load fell
below 15, which happened at 21:24 (13.52). The agents were resumed with one new rule: run every Gradle
command in the background with a long timeout, one at a time, and wait for it. Excluding the worktrees'
build output from Spotlight was suggested to the owner, as a system setting the coordinator would not
change itself.

At 21:25 a second push of #408's branch revealed something. The push "killed" at 20:12 had succeeded: the
remote branch was already at `78c462c`.

## #403, #374 (21:43–22:19)

- **#408 (#403).** CI passed on all three OSes. The critic's check, resumed after the limit, passed. It
  found one Low that needs a race to the microsecond and an identical copy of the grading's own frames. It
  was accepted as the same class as #361's window. Merged as `b5d79f0` at 22:03.
- **#374 (PR #409).** Still unpushed, the branch finished its rebase onto #386's reviewed head. It then
  moved onto main `4700074` (`3903c38`) and onto `b5d79f0` (`084e54d`), the last time with no conflict.
  The gate critic attacked it on the host and in the image:
  - `.ps` was swapped for a link at ten points, for five operations, with the original directory either
    deleted or moved: 100 runs. Nothing was written outside the checked `.ps` in any of them.
  - The open file descriptors stayed at 65 after 10,000 appends.
  - A healthy sequence of writes produced files byte-identical to the merge base's, on the host and in
    the image.
  - It left four Lows and the still-open chained `insteadOf` from #404.

  Merged as `e302739` at 22:19.

## #402 and #405 (PR #410)

#402: a token-shaped name the destination already holds is not new. #405: a push URL that `ls-remote`
would rewrite again is not pushed to, and held tips reach `rev-list` on stdin. Finishing #405, the
implementer found two fail-opens in its own check, `d6d5fe7`. A timed-out rule listing and a rule base
with a space both passed. Both were fixed (`e9993af`).

After the snapshot ends (from `progress.md` and the squash message):

- **Windows CI.** Nine rewrite tests failed on windows-latest. They built rules as `C:\…\A/`, and git
  matches `insteadOf` as a string prefix, so that never prefixes `C:\…\A\repo.git`. The tool rewrote
  nothing, and nothing leaked. The tests now write rules and URLs with forward slashes.
- **The gate critic's M1.** #405 had put a `--not` line on `rev-list --stdin`. Git reads options there
  only from 2.42. In the critic's Debian 12 image, git 2.39.5 refused every commit and every push. That
  failed closed, but on that git nothing could be committed or backed up. Listings now leave objects out
  as `^<id>`, and a line that is not a revision is refused before git is asked.
- **The gate critic's L1.** Push URLs are now read as git reads them:
  - an empty `pushInsteadOf` value;
  - only git's own newline taken off what `ls-remote --get-url` prints;
  - a remote named for its own push URL;
  - a remote defined only in the global config.

Merged as `794c37f` at 23:40, closing #402 and #405.

## #407 (PR #411)

From `progress.md` and the squash message. #407 put a guard on each regenerated file, so one page that
cannot be replaced no longer ends the vault refresh. A failed `ATOMIC_MOVE` now falls back only on
`AtomicMoveNotSupportedException`.

- **Windows CI.** The Windows-only held-open test failed with `NoSuchFileException: Users`. The test
  itself deleted `rest + path`. A `Path` is an `Iterable<Path>`, so Kotlin's `+` took the list overload
  and added the path's name parts, `Users` first. The fault was in the test.
- **Review.** The guard moved into `RecordWrites.replaceOrSkip`, so every writer that carries on gets it.
  A link at the target is now taken away and the move made again.

The Windows pins, a page and a tag note held open and a real link to a directory, ran green on
windows-latest. Merged as `4af0ab9` at 23:30.

## The final live check (23:41, main `794c37f`)

The container was rebuilt with every PR from #355 to #411. From the coordinator's notes:

- **Boot.** Healthy, with 0 WARN or ERROR lines. That includes no `DirectoryHandle` fallback warning: the
  image's Linux gives `SecureDirectoryStream`.
- **MCP.** The 36 snapshot files were byte-identical to the container at `41f713e`.
- **The records repository.** Its 255 files were byte-identical except `.gitignore`, which gained #386's
  `.*.programmers-tracker.tmp` rule. The boot's reconcile committed that as `b1e116e`
  "chore: reconcile uncommitted records": five lines, nothing else. It is one commit ahead of origin until
  the next backup push. The modes were unchanged: 61 `rw-------` and 194 `rw-r--r--`.
- **#365.** `tools/call submissions` with `"arguments": "x"` answered HTTP 200 with JSON-RPC `-32602`,
  "arguments must be an object". `stats` with `{"groupBy": "verdict", "a, b": 1}` answered `isError` with
  `unknown argument(s): "a, b"; stats takes groupBy`.

## The tally

- **24 PRs merged** between 09:57 on 10-08 and 23:40 on 10-10, from #371 to #410. They closed 25 issues:
  - the seven open at 09:46 on 10-08;
  - #370, from the owner's first `exam_prep` run;
  - 17 filed during the window from reviews and implementers' reports: #372–#378, #381, #385–#387,
    #390, #394, #402, #403, #405 and #407.
- **Working time** was about 19 hours: 09:46–18:12 and 20:37–23:51 on 10-08, and 16:11–23:41 on 10-10.
  The gaps were the owner's trip home and the usage limit.
- **Left for this ingest:** the live checks above, the index duplicate, and the session's operational
  events.
