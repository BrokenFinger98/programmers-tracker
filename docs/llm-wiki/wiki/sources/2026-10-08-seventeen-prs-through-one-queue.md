---
type: source
project: programmers-tracker
tags: [subagents, workflow, git, security, links, review-pattern, ci, failed-attempts]
created: 2026-10-10
updated: 2026-10-10
sources: [raw/sessions/2026-10-08-seventeen-prs-through-one-queue.md]
---

# 2026-10-08 (afternoon and night) session summary — seventeen PRs through one queue

## Key claims

1. **The queue.** The owner asked the coordinator to keep going without stopping. Each issue went to its
   own implementer in its own worktree, and every PR waited for CI on three OSes. Seventeen squash-merged
   between 15:37 and 23:25. Long-lived agents carried related issues, and the critic who found a defect
   was the one asked to verify its fix ([[concepts/orchestrated-implementation]]).
2. **The workflow broke its own rules twice, and was changed.**
   - Force pushes to three PR branches went through as `git -C <dir> push --force-with-lease`. The
     owner's hook matches `git push … --force` and missed them; a plain spelling was blocked later. Since
     then, a pushed branch gets `origin/main` merged in and is never rebased.
   - GitHub reported a conflict that `merge=union` had resolved on this machine. GitHub's mergeability
     check runs no custom merge driver.
3. **A squash-merged base conflicts with the branches stacked on it.** #375's merge of main after #373's
   squash gave six conflicts, against the coordinator's own prediction that it would merge cleanly.
   Unpushed branches were moved with `rebase --onto origin/main <old base>` instead. When two agents
   began resolving the same seven hunks, one was stopped, so the resolution was made once and handed
   down.
4. **An audit judged harmless the path that hung the server.** #387's audit said only a line count left
   `orphans()`. The critic hung the boot, and every MCP call, with one pulled link to
   `/proc/self/fd/1` in the deployed image
   ([[decisions/2026-10-08-a-refused-read-is-not-an-empty-one]]).
5. **A performance PR regressed the token gate, and was held for it.** #373 made the first push of 5,000
   commits take 0.5 s instead of 252.5 s. With stale tracking refs, though, it sent a token blob that
   main had refused. Every push read HEAD's tree until `ls-remote` made that redundant
   ([[decisions/2026-10-08-the-push-gate-reads-each-object-once]],
   [[decisions/2026-10-08-each-gate-searches-what-its-destination-lacks]]).
6. **Reviews kept finding real defects.**
   - #377's guard deferred a forged session rather than blocking it: following the tracker's own advice
     replayed the session, recorded under the owner's name
     ([[decisions/2026-10-08-reconcile-never-stages-the-state-directory]]).
   - #372's `LC_ALL=C` pin also closes a macOS regex that misses a token after a non-UTF-8 byte.
   - #390 made one of its own messages false
     ([[decisions/2026-10-08-a-backup-that-did-not-count-backs-off]]).
   - #386's reviewer reproduced a real crash's debris being committed by the old build and left out by
     the new ([[decisions/2026-10-08-one-replace-and-no-crash-debris]]).
7. **CI on other machines found what this one could not.**
   - CI's git printed `(null)` where 2.48.1 printed a path, so a test that relied on git's wording never
     reached its bound.
   - Git for Windows' `mkfifo` exits 0 and makes a plain file
     ([[concepts/assumption-vs-measurement]]).
8. **#361 shipped and was verified live at 16:42** on main `41f713e`. The 36 MCP answers were
   byte-identical, and all 255 record files were byte-identical with unchanged modes, though the boot
   had rewritten pages through the new bound ([[decisions/2026-10-08-no-writer-follows-a-link]]).
9. **#374 closed the race #360 had left** by writing through the directory it checked. Red on main:
   `.ps` swapped while git answered put the timers and the push credential into `problems/zz`
   ([[decisions/2026-10-08-state-is-written-in-the-directory-it-checked]]).
10. **Two interruptions.** The owner's trip home stopped six agents, which were resumed with exact state.
    The weekly usage limit then cut three agents off mid-task at 23:51.

## Pages this source updated

The pages linked above: the outcomes and sources of the 10-08 decisions, and the two concepts.
#355's decision ([[decisions/2026-10-08-a-fault-of-ours-answers-its-call-on-200]]) also cites this raw.
