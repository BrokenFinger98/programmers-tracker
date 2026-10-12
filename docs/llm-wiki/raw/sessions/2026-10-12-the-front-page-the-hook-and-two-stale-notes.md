# 2026-10-11 / 2026-10-12 — the front page, the hook, and two stale notes

Raw session record. Immutable (wiki schema §1). 2026-10-10 23:42 to 2026-10-12 11:08 KST, session
`b240e44e`, sliced by KST from the session transcript. Continues
`2026-10-10-the-limit-the-load-and-the-last-five.md`.

- **To 08:59 on 10-12:** the inbox snapshot, taken before a compaction.
- **08:59–11:08:** the live transcript.
- **The agents' work** is quoted from their completion notices in the coordinator's transcript. The
  seal digest on the copy is from the #414 implementer's own transcript.

---

## The follow-up series closes (10-10 23:42 → 10-11 00:34)

- **23:42.** The #412 ingest went to an agent in its own worktree. Finished worktrees and merged local
  branches were removed.
- **00:16.** The agent finished with one blocker. The owner's danger hook had refused a plain `rm` of the
  consumed inbox snapshot, reading the absolute path as a dangerous target. The agent did not step
  around it.
- **00:23.** PR #413 merged as `7991014` after seven green checks. No issue and no PR was open.
- **00:34.** The owner deleted the snapshot by hand.

## What the "MCP strengthening" had been (00:35–00:37)

The owner asked what all those issues and PRs had actually done, since the goal had been to strengthen
the MCP server. The coordinator's accounting:

- **The MCP work itself** was three or four PRs: `repair_steps` (#357, the session before), the
  `exam_prep` prompt (#367), the judge-output fallback (#371), and protocol cleanup (#382, #384, #393).
- **The rest came from reviewing it.** MCP tools read the records repository. A pull can fill that
  repository, and it is pushed to GitHub beside a stored push token. Reviewing that read path measured
  a leak through one link (#354). From then on every PR met an adversarial critic, and 9 of 11 found a
  real defect.
- **Its own assessment.** Real value: the token gate, the link bound, the boot-hang fix (#401) and the
  backup fixes. Wider than the repository's strict YAGNI: the tail, such as a chained `insteadOf`
  (#405), a microsecond race (#374), a crash instant (#403) and Windows-only paths. It proposed that
  low-risk items found by review be filed, with the owner asked for their priority, rather than fixed
  at once.
- **What the owner sees:** one new prompt. The 36 MCP answers were byte-identical before and after the
  rebuild.

Asked whether anything remained, it answered that no development work did. Left were one check (the
first real push through the gate), three decisions for the owner, the accepted costs, and an old
backlog. Two items in that answer were wrong (below).

## The first real push through the gate (10-11 23:00)

The daily backup logged "Daily backup pushed the record repository" at 23:00:47. At 08:35 on 10-12,
after a fetch, the records repository was level with origin. `b1e116e`, waiting since the 10-10
rebuild, was the first push of real records through the content gate rebuilt in the #360 series.
Nothing was refused.

## Two stale notes (10-12 08:35–08:40)

At 08:35 the owner asked again what was left to decide and what came next. Two of the coordinator's
notes did not survive the morning:

- **`domain/calc/runner` coverage**, offered as backlog at 00:37, was not open. The coordinator corrected
  it in its 08:36 answer. #302 had retired the exemption on 2026-08-14 at 83%, above the 80% floor, as
  the coverage ADR and `build.gradle.kts` record. The note had not been updated since.
- **Private vulnerability reporting** was still called disabled in that 08:36 answer. After the owner's
  decision below, the API answered `{"enabled": true}`. `SECURITY.md` already says to use it "if this
  repository offers it", so nothing changed.

Both were notes repeated as facts without being measured again.

## The owner's three decisions (08:39)

1. **The front page.** "Let's go with the root": the records repository's root `README.md` becomes the
   regenerated index (#414).
2. **Private vulnerability reporting.** The owner sent a screenshot of the setting and told the
   coordinator to turn it on itself. It was already on.
3. **The danger hook.** The coordinator had offered a patch for the owner to apply. The owner asked it
   to make the change itself: "Fix it. Can't you do it yourself?"

The PR body and the squash message of #415 date this decision 2026-10-11. The transcript has it at 08:39
on 10-12, the date the ADR gives. The questions that led to it were asked on 10-11.

## The hook (08:40–09:45)

`~/.claude/hooks/block-danger.sh` is the owner's global PreToolUse hook, outside this repository. It
enforces this repository's practice of never force-pushing: a refused command is for a person to run.

**What it missed.** Every git pattern required the subcommand right after `git`. So all of these
passed: `git -C <dir> push --force`, `git -c k=v push …`, a `+refspec` push, `--mirror`, a bundled
`-uf`, and `reset --hard` or `clean -f` after `-C`. The three force pushes of 2026-10-08 had gone through
this way. That was an accidental bypass, not permission, and the coordinator's memory already said never
to step around the hook.

**The first draft was too broad.** It let any words sit between `git` and the subcommand. Checking the
evidence-gate trigger with the same prefix, the coordinator saw that `git commit -m "fix: merge …"` would
then read as a merge, and message prose as a force push. The draft was narrowed. Only git's global
options may sit between: `-C`, `-c`, `--git-dir`, `--work-tree`, `--namespace`, `--config-env`,
`--exec-path` and `--attr-source` with their argument, quoted or not, and other dash options. The
evidence-gate trigger also stopped firing on `merge-base`, `merge-tree` and `merge-file`.

**Measured.**
- **Cases.** 41 danger cases and 10 evidence-gate cases, all as intended. Sixteen forms the old hook
  missed are caught: 14 dangerous and 2 that the evidence gate now sees.
- **Replay.** Every unique Bash command in this project's transcripts went through the old and the new
  hook.
  - First draft (08:42–08:59): 7,564 commands, 5 verdicts changed.
  - Narrowed (09:04–09:34): 7,575 commands, 6 changed, all ALLOW→BLOCK, none BLOCK→ALLOW.
  - Four of the six were real pushes the old hook had let through: three `git -C "$W" push -q
    --force-with-lease` from 2026-10-08, and a `git push -q --mirror` from 2026-09-21 that filled a new
    private repository. Two were prose inside a heredoc, one of them the coordinator's own edit of the
    hook.
- **The gate trigger.** The replay's second half, with an incomplete evidence gate, was stopped at the
  30-minute background limit. The two trigger regexes were compared directly instead, over 7,578
  commands: 693 gated before and 719 after.
  - 49 newly gated: 46 `git -C … push`, one `git -C … merge --abort`, and two lines of prose in
    heredocs.
  - 23 no longer gated: 14 `merge-base`, `merge-file` or `merge-tree`, and 9 lines of prose quoting
    `git push`.
  - The coordinator read the 49 as all `git -C` forms. Re-deriving the comparison at ingest gave the
    same totals, with the two prose lines among them.
- **The grep.** The hook's bash resolves `/usr/bin/grep`, BSD grep 2.6.0. The interactive shell's `grep`
  is ugrep 7.8.4. A regex checked in the shell is not the regex the hook runs, so every case ran through
  `bash`.

**Installed at 09:45.** The installed file is byte-identical to the tested one, and the 51 cases were
rerun against it. Live: `git -C /nonexistent-dir-hook-check push --force origin x` was refused by the
installed hook. The pre-change copy is kept outside the repository. The coordinator's memory note now
says the hole is closed.

**Remaining.**
- **Prose in a heredoc.** Prose naming a force push inside a Bash heredoc is still refused, as it was
  under the old hook, because the hook reads the whole command string. It happened at 09:03, when the
  old hook refused the coordinator's own file of test cases, and it had happened on 10-08 to a memory
  note. Such text goes through the Write tool.
- **The rm check.** The unchanged rm check reads `-programmers` in a file name as a recursive flag. So
  removing such a file by an absolute path is refused, which is what stopped #412's agent. A relative
  path passes. It was outside the request and was not changed.

## #414 → PR #415 (08:43–11:02)

The design is in the ADR `2026-10-12-the-front-page-is-the-index`, merged with the code.

- **08:43.** Issue #414, and one implementer in its own worktree. Five commits on `7991014`:
  - red against a stub: 32 of 82 tests in five classes failed;
  - mutation: 24 mutants, 23 killed at first. The survivor dropped the retirement's INFO line. A test
    pinned it, and the rerun killed it;
  - measured on a copy of the live records repository: boot 1 made one commit (`6123161`), boot 2
    changed nothing, and an edited `README.md` was kept. The copy's front page carried the seal digest
    `badb8264…`.
- **09:47.** Pushed, and PR #415 opened. At 09:58 CI was green at `b94053e`, seven checks.
- **Critic round 1 (10:10).** Nothing blocking, and no path that loses the owner's content. Each finding
  was reproduced with a Java harness against the branch's compiled classes, one marked plausible.
  - **Three Important:**
    - the seal's note words were part of the check with nothing protecting them, so a rewording would
      freeze every deployed page;
    - the old pair retired unevenly: an owner who edits `README.md` keeps it, and it links a
      `README.ko.md` that is removed;
    - a github.com edit ends in a merge conflict, because the server never pulls.
  - **Six Minor:**
    - an empty history zeroed the front page;
    - retirement rests on the per-machine ledger (a clone, or a CRLF checkout);
    - `removeFile` threw for a missing root, a regression of this PR;
    - retirement did not check that its replacement exists;
    - log levels: the owner's own page was warned, against the code's "Stated, not warned";
    - the 2026-08-13 ADR's line that a deleted README comes back was not marked old.
  - **Test gaps.**
  - The critic asked for 1–3 before the merge and left 4–9 to follow or be accepted.
- **The coordinator's decisions (10:12):**
  - the seal checks its framing, not its words;
  - the pair retires together;
  - a github.com edit is documented, and an accepted cost;
  - an empty history leaves the page;
  - `removeFile` answers true for a missing root;
  - a guide replaces a README only while it is a regular file;
  - an owner's page is stated at INFO, and a refused read is a WARN;
  - the old ADR is marked;
  - the tests are added.

  For the clone and CRLF case the critic proposed matching the hash of every shipped guide. That was
  declined as YAGNI and written as an accepted cost.
- **10:30.** Six commits, 2,781 tests green. Pushed as `8b3f4c9`, and CI was green at 10:41.
- **Critic round 2 (10:39).** Every round-1 finding closed or accepted. The fixes had introduced four
  Minor issues:
  - **A quadratic regex.** The new seal pattern backtracked on a hostile first line: 173 ms at 112 KiB,
    695 ms at 225 KiB, 15 s at 1 MiB, about 4 min at 4 MiB. It ran at every grading, and at boot, which
    is the class of #387's FIFO. It became a hand parser capped at a 1,024-character first line. The
    critic's probe then answered in 0 to 4 ms from 7 KiB to 4 MiB.
  - **Text after the seal.** Owner text after the seal's `-->` on the same line was overwritten. A seal
    is now exactly one comment, whose first close is its end. The implementer rejected `--!>` too,
    since a browser also closes a comment there.
  - **A twin kept forever.** The Korean twin stayed beside any README that never names it. It is now kept
    only while the README's bytes contain `README.ko.md`.
  - **The docs.** Edit any file in the clone, never on github.com; the owner's page is stated at INFO;
    and git's real rejection for a clone that never fetches is "(fetch first)". The implementer's
    round-1 quote, "the tip of your current branch is behind", came from a copy of the critic's
    simulation, a clone that had fetched. The tracker never fetches.
- **10:51.** Three commits, 2,786 tests green. Pushed as `8af6a30`. CI was green at 11:02, and the PR was
  squash-merged as `4583f36`: 28 files, +1,767/−67, from 14 commits.

## The live check (11:03–11:04, main `4583f36`)

The container was rebuilt from `4583f36`. Boot 1 started at 11:03:41.

- **One reconcile commit** on the records repository, `6ccde3e`, "chore: reconcile uncommitted records":
  `R086 README.ko.md → GUIDE.ko.md`, `A GUIDE.md` and `M README.md`. No other tracked file changed, and
  the tree was clean. The copy, measured before the reviews added the editing advice to the guide,
  showed R090.
- **The log.** "Wrote /records/GUIDE.md — seeded once; it is yours to edit from here", and the same for
  `GUIDE.ko.md`. "Took /records/README.md away, still exactly as this server seeded it: the index takes
  its place", and the same for `README.ko.md`. 0 WARN or ERROR.
- **The front page.**
  - Seal digest `badb8264…`, the copy's digest under the reworded note: the digest covers only the
    body, and the records were the same.
  - "25 problems recorded, 25 passed."
  - 26 table lines, as in `problems/README.md`.
  - 27 relative links, 0 unresolved.
  - `README.ko.md` is gone, and `GUIDE.ko.md` opens with its `translated-from` line.
- **Boot 2** (restart at 11:04:13). HEAD stayed `6ccde3e`, `README.md` stayed byte-identical, the tree
  stayed clean, and there was 0 WARN or ERROR.
- **Ahead by one.** The records repository is one commit ahead of origin. The server never pulls; the
  next pass or the 23:00 backup pushes it through the token gate. The coordinator did not push from the
  host, so the push would go through the server's gate.

## Wrong or stale beliefs

- **"Private vulnerability reporting is disabled":** stale. It was on.
- **"`domain/calc/runner` coverage is backlog":** stale since 2026-08-14.
- **The hook's first draft:** any word between `git` and the subcommand, which would have read
  commit-message prose as a command.
- **"All 49 newly gated are `git -C` forms":** two were prose in heredocs.
- **Round 1's quoted rejection:** measured on a clone that had fetched.
- **The decision's date:** 2026-10-11 in the PR body and the squash message, 08:39 on 10-12 in the
  transcript.

## The tally

- **10-12, 08:39 to 11:04.** Work on #414:
  - one issue and one PR, from 14 commits;
  - two critic rounds;
  - three green CI runs of seven checks each (`b94053e`, `8b3f4c9`, `8af6a30`);
  - one merge and one live check.

  Besides it, the hook was rebuilt and installed, and one setting was found already on.
- **Left open:**
  - `6ccde3e` waits for its push;
  - github.com shows the index once it is pushed;
  - the hook's heredoc and rm limits remain.
