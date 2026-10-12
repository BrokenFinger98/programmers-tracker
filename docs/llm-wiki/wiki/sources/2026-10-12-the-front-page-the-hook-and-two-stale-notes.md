---
type: source
project: programmers-tracker
tags: [vault, seeds, review, git, hooks, live-verification, measurement]
created: 2026-10-12
updated: 2026-10-12
sources: [raw/sessions/2026-10-12-the-front-page-the-hook-and-two-stale-notes.md]
---

# 2026-10-11 / 2026-10-12 session summary — the front page, the hook, and two stale notes

## Key claims

1. **The records repository's front page is its index, verified live.** #414 went through one PR and
   two critic rounds in a morning, and merged as `4583f36`
   ([[decisions/2026-10-12-the-front-page-is-the-index]]). On the owner's repository:
   - the first boot made one reconcile commit, `6ccde3e`: the Korean guide renamed, `GUIDE.md` added
     and `README.md` rewritten as the index, with nothing else touched;
   - the second boot changed nothing;
   - the seal's digest equalled the copy's under reworded note words, as the "words are free" rule
     says it should.
2. **The ledger retired its first seeds.** Both old READMEs still held the bytes the ledger had recorded,
   so both were taken away and said at INFO
   ([[decisions/2026-08-14-the-seed-ships-in-the-form-its-reader-rewrites-it-to]]). The seeds this
   repository's preparation names are now the guide's
   ([[decisions/2026-08-13-the-server-prepares-the-repository]]).
3. **A second review round is a review of the fixes.** Round 1 found three Important and six Minor
   findings, none blocking. Round 2 found four defects that the round-1 fixes had introduced. The worst
   was a seal regex that took 15 s on a hostile 1 MiB first line, on the boot path
   ([[concepts/orchestrated-implementation]]).
4. **The danger hook's gap is closed, and was measured against history.**
   - A global option between `git` and the subcommand had let force pushes through.
   - The first draft of the fix was too broad.
   - The narrowed one changed 6 of 7,575 real commands' verdicts, all to BLOCK. Four of them were
     pushes the old hook had missed.
   - The hook's bash runs BSD grep, not the shell's ugrep.
   - Prose in a heredoc and an absolute-path `rm` of a `-programmers` file are still refused.
5. **Two notes had been repeated as facts.** Private vulnerability reporting was already on. The runner
   coverage exemption had been retired two months earlier. A quoted git rejection had come from a clone
   unlike the tracker's ([[concepts/assumption-vs-measurement]]).
6. **The first real push through the rebuilt token gate.** The daily backup pushed `b1e116e` at 23:00 on
   10-11, and nothing was refused
   ([[decisions/2026-10-08-reconcile-never-stages-the-state-directory]]).
7. **What the "MCP strengthening" had been.** By the coordinator's account it was three or four MCP PRs
   and a long tail of fixes from reviewing them. The coordinator proposed that low-risk review findings
   be filed and prioritised by the owner rather than fixed at once.

## Pages this source updated

The pages linked above.
