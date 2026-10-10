# 2026-10-08 — the token gate took four rounds

Raw session record. Immutable (wiki schema §1). 02:57–03:05 and 09:46–14:57 KST, session `b240e44e`,
sliced by KST from the session transcript. Continues `2026-10-08-the-prompt-only-the-owner-can-run.md`
(00:00–02:36) and `2026-10-08-the-first-exam-prep-run.md` (09:11–09:46, the owner's own run). The next
stretch of the same day is `2026-10-08-seventeen-prs-through-one-queue.md`.

---

## Before the owner woke (02:57–03:05)

The #368 ingest agent finished at 02:57 with one local commit. Before pushing, the coordinator searched
the commit for secrets, emails, absolute paths and record data. One hit came back: the commit's own author
line, not its content. PR #369 merged as `7be8a08` at 03:03, with no AI trailer. `goal.md` was rewritten.

The morning report gave the owner one step, the `exam_prep` run, and one decision. 117 of the 180 commits
on main carry an AI trailer that the repository's commit skill forbids. Should they be rewritten? Seven
issues were open: #355, #356, #360, #361, #362, #365 and #366.

## Whether it was finished (09:46)

After the first `exam_prep` run, the owner asked whether everything was now done, with nothing left to
improve or fix. Before answering, the coordinator checked main's CI and the open issues. It then said
no. What had been built was verified, but using it had shown three limits:

- **The diagnosis is shallow.** 58 of the 63 steps carry no diff, because run code is kept only from
  2026-10-07. No code change fixes that. More solving does.
- **The prompt text should name the fallback.** Patterns named from the judge's output had worked, and
  the text said nothing about that case.
- **Measurement runs mix with mistakes.** The owner's test runs on 59035 and 59036 sit in the records
  beside real mistakes, and nothing in the records tells them apart.

The seven open issues were ranked. #360 was ranked first because a linked `.gitignore` could get the push
token committed and pushed. The recommended order: record the acceptance, then #360 and #361, then the
prompt wording, then the rest. On the trailers, the coordinator recommended leaving the 117 commits alone,
because rewriting protected main breaks every hash and PR link. The owner's answer was a one-word
instruction to continue. The coordinator took that as accepting both recommendations and said so in its
reply.

## #370 and its live check (09:48–09:58)

One PR covered both items: the prompt now names the pattern by the judge's own output where a step has no
diff, and the first run's raw went into the wiki. The new test failed first. PR #371 merged as `61ead2d` at
09:57. The rebuilt container came up healthy, with 0 WARN or ERROR lines. A modern `prompts/get` with
`language: "mysql"` answered HTTP 200 with the fallback sentence in a 2,144-character message (measured,
09:58). This check was not written into the ADR or `progress.md` at the time.

## #360, round 1 (09:59–11:03)

The brief came from the coordinator's own design: exclude `.ps` with a pathspec, warn when git does not
ignore it, and never write `.gitignore` through a link. The implementer measured the plan first, and it
failed.

- **The planned pathspec breaks every healthy repository.** `git add --all -- . ':(exclude).ps'` exits 1
  wherever `.ps/` is ignored: it stages the rest, then reports the ignored path. All eight spellings
  starting with `.ps` behaved the same, and git 2.48.1's source showed why (`exclude_matches_pathspec`).
  `:(exclude,glob)[.]ps/**` exits 0 in all five cases tried.
- **The planned probe misfires.** `check-ignore -q .ps` called a working rule broken twice: before `.ps`
  exists, and when a `.ps` file was staged by hand. `--no-index .ps/` does not.
- **Two older bugs.** Git's warning about a linked `.gitignore` arrives on stderr. The dirtiness check read
  stdout and stderr together, so every reconcile tried an empty commit. And a `.gitignore` that was not
  UTF-8 was read as empty and overwritten.
- **Git follows no `.gitignore` link at all**, even one that leads to a valid file holding the rule.

Six commits; 1,945 tests. The coordinator tried the glob form in the tracker's container (git 2.53.0).
The owner's danger hook blocked its probe script, which matched the hook's recursive-delete pattern. The
quality reviewer later made the same checks on 2.53.0.

At 10:45 the owner asked whether issues were ever closed, since they all seemed to stay open. They are:
a `Closes #N` line in the squash body closes the issue at merge. Nine had closed in this session. The seven
still open were review follow-ups, and that list was the remaining work.

The two reviews came back at 11:02 and 11:03.

- **Quality:** mergeable. One Important: the new permission copy in `AtomicStateFile` was not guarded,
  while three other places in the code already guarded the same call.
- **Adversarial:** the fix could not be trusted to close #360. The threat model was the issue's own:
  content that arrives by clone or pull. Four more routes put the token into a commit and a push to a
  bare remote. Each was reproduced with the production classes, and each leaked on main too:
  - **F1:** a `.pſ` directory. APFS folds U+017F to `s`, so the server opens it as `.ps`. Git's `icase`
    folds ASCII alone, so neither the rule nor the pathspec matched it.
  - **F2:** a tracked link where `.ps/git-credentials` stands. A pull replaced the ignored file without a
    word, and the next boot wrote the token through the link into `problems/`.
  - **F3:** `.ps` itself a tracked link. One `git pull` deleted the ignored directory, frames and token
    with it, and git exited 0.
  - **F4:** `.PS`.

  F5 was a push that sent another tool's commit.

The coordinator first checked the owner's records repository by file names only: nothing had ever been
committed under any spelling of `.ps`. It filed #372 for two findings outside the token path: the daily
backup never read reconciliation's answer, and a directory git cannot open was skipped without a word.
Then it sent round 2.

## Round 2 (11:05–12:13)

The implementer reported one fact the brief had not foreseen. In the owner's real deployment, a Linux
container over a macOS bind mount, `toRealPath()` of `.PS` or `.pſ` answers `.ps`. A real-path comparison
alone lets the alias through. A check that the root's listing holds exactly `.ps` was added. It was then
measured in a Linux JDK 25 container on a bind mount: without it two alias tests fail, and with it all
seven pass.

`@{u}` fails on both git versions, because the tracker sets no upstream. The push range became
`rev-list HEAD --not --remotes`. The branch had 14 commits and 1,978 tests.

- **Quality:** mergeable, seven Minors. Two were substantive. Another remote could mask the push range;
  a token commit on a second remote went to origin unsearched (measured). And a refused commit left the
  token file staged.
- **Adversarial:** F1–F4 were closed on the host and in the image, with no regression against main. N2
  was a new High. A pulled `.ps/git-credentials` that git tracks gets the real token written into it at
  boot. The server does not commit it, but Obsidian Git or an IDE's "commit all" would. N1 and N7 were
  the same family. N4 and N5: another remote could mask the range, and push settings could send refs
  beyond HEAD. R1: after the advised rotation, the old token in history was no longer searched.

The coordinator bounded round 3 and filed #373 (scan cost), #374 (the race between check and write) and
#375 (commit and tag messages) for what it left out.

## Why findings kept coming (13:05)

The owner remarked that new findings kept appearing. The coordinator agreed and gave three reasons. The
threat model is wide, since anything a pull delivers is the attacker's. OS and git quirks stack: case
folding, `ſ` folded to `s`, a bind mount reporting another real path. And each closed layer is where the
reviewer attacks next.

It also said severity was falling. Round 1 found the server's own commit and push. Round 2 found other
tools picking up what the server had written, plus unusual git configurations. It set a stopping rule:
finish round 3, attack once more, and merge. After that, anything needing an unusual configuration or a
race becomes an issue. Every route so far needed push access to the records remote, and the real
repository was clean.

## Round 3 (12:13–13:26)

26 commits and 2,012 tests. The final attack pass blocked the merge.

- **U1, High (adversarial).** On APFS a pulled `.pſ/x` lands inside the real `.ps/`, while the index
  records `.pſ/x`. The new tracked-state probe asked git with `icase`, which folds ASCII alone, and saw
  nothing. So the server committed and pushed its own raw frames and timers, and wrote the token into a
  tracked file. Reproduced with a real `git pull` and the round 3 boot jar, on the host and in the image.
- **Critical (quality), and the coordinator's own design.** Round 3 had every state write walk `.ps`
  first. The walk raced the tracker's own temp-and-rename writes. On a healthy repository it refused 444
  of 3,000 inspections, and 3 of 500 sessions lost every frame. That breaks the constitution's rule
  against discarding originals. The coordinator told the owner the regression came from the design it
  had instructed.
- **Three Importants:** a repository with no remote searched its whole history every minute; frames were
  dropped while `.ps` was refused; and the fail-closed branches were untested.

## GitHub's API stopped answering (13:27–13:58)

At 13:39 the owner asked whether GitHub was still unreachable. Measured: GitHub's status page was green,
and `github.com` worked, as did git push and fetch. Only `api.github.com` failed, at the address DNS gave
this region: the TCP connection opened and HTTPS never answered. The same API's other regions answered in
0.5–0.9 s, so `gh` was unusable and nothing else was.

The coordinator's background wait for recovery hit its 30-minute limit. Issues #376 (stale remote-tracking
refs, and a false alarm over content already published) and #377 (a pulled raw session replayed at boot)
were filed by calling another region's address with `curl --resolve`. System files were left alone: an
`/etc/hosts` line was suggested to the owner, not written. PR #379 was opened, watched and merged the same
way, and the PR, CI and merge scripts built for it served the rest of the day.

## Round 4 and the merge (13:27–14:57)

34 commits and 2,043 tests.

- **One `GitProcess`** carries every git call, with a pinned environment. An inherited
  `GIT_LITERAL_PATHSPECS=1` had made the tracked-state question answer "nothing".
- **U1 closed.** Every index entry is judged by its first segment, in Java's case folding or by
  `isSameFile`.
- **No walk.** The review's own probes gave 0 of 3,000 refused and 0 of 500 sessions short of a frame.
  Refused frames are now held in memory.
- **An unreadable blob refuses.** `git grep` exits 1 after an `error:` line, which had read as "nothing
  found".

The implementer reported that two parts were written before their tests. Red was confirmed afterwards, on
the old code.

The coordinator stated the blocking rule before the last passes: only the server's own leak, or data
lost on a healthy repository, blocks. Both passes found it mergeable. What they left beside #376 and #377
went to #378.

PR #379's Windows leg failed twice.

- **Test setup** (`9e780e1`). Git writes objects read-only, which Windows will not delete. The runner's
  `core.autocrlf` warning was read as an object id.
- **A product bug** (`91706c8`). On Windows, receive-pack still holds git's output file after `git push`
  to a local path exits. The cleanup in `finally` threw and discarded the answer already read. Cleanup is
  best-effort now.

All seven checks passed, and #379 merged as `ccca9e6` at 14:54.

## #360 live (14:55)

The container was rebuilt from `ccca9e6`. It came up healthy with 0 WARN or ERROR lines. The real `.ps`
on the bind mount was judged usable, and the startup reconciliation ran (`recorded=0`).

In the records repository, HEAD stayed at `7e144fa` and the status was clean. No `.p*` path had ever been
committed, nothing under `.ps` was tracked, and `.ps` was a real directory. The 36 MCP snapshot files
matched the post-#364 snapshot. A real push through the gate was not exercised. The next stretch starts
at 14:57 with #361.
