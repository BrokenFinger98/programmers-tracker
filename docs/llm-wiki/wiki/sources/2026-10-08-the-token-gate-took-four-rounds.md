---
type: source
project: programmers-tracker
tags: [security, git, links, review-pattern, measurement, ci, failed-attempts]
created: 2026-10-10
updated: 2026-10-10
sources: [raw/sessions/2026-10-08-the-token-gate-took-four-rounds.md]
---

# 2026-10-08 (morning) session summary — the token gate took four rounds

## Key claims

1. **Asked whether the work was finished, the coordinator said no, with reasons.** Using `exam_prep` once
   had shown three limits:
   - 58 of 63 steps had no diff;
   - the prompt text did not name the judge-output fallback;
   - measurement runs sit in the records beside real mistakes.

   The seven open issues were ranked, with #360 first. The 117 commits carrying an AI trailer were left
   as they are, since rewriting protected main breaks every hash and PR link. #370's text change was
   verified live at 09:58: `prompts/get` carried the fallback line, 2,144 characters
   ([[decisions/2026-10-07-exam-prep-asks-in-the-open]]).
2. **The planned fix was measured before it shipped, and it would have broken every healthy reconcile.**
   `git add --all -- . ':(exclude).ps'` exits 1 wherever `.ps/` is ignored. The planned probe misread a
   working rule twice. Two older bugs came out on the way: empty commits caused by git's stderr, and a
   non-UTF-8 `.gitignore` overwritten
   ([[decisions/2026-10-08-reconcile-never-stages-the-state-directory]]).
3. **Four adversarial rounds, each finding what the last one had not.** All findings were reproduced with
   the production classes, and the routes leaked on main too.
   - Round 1: F1–F4, all High, plus F5.
   - Round 2: N2, High, among N1–N11 and R1.
   - Round 3: U1, High, which blocked the merge. The quality reviewer added a Critical: the walk the
     coordinator's own round-3 design required raced the tracker's writes. It refused 444 of 3,000
     inspections and lost every frame of 3 sessions in 500.
   - Round 4 was a regression check against a blocking rule stated in advance.

   Severity fell round by round, which is what justified stopping
   ([[concepts/orchestrated-implementation]]).
4. **The deployment answered differently from the host.** Over the macOS bind mount, `toRealPath()` of
   `.PS` or `.pſ` answers `.ps`, so only a listing check catches the alias. APFS folds `ſ` to `s`, while
   git's `icase` folds ASCII alone. `@{u}` does not exist, because the tracker sets no upstream
   ([[concepts/assumption-vs-measurement]]).
5. **GitHub's API stalled at one regional address while everything else worked.** Issues, PRs and CI
   were reached through another region with `curl --resolve`. System files were left to the owner.
6. **Windows CI found a product bug the reviews could not.** `GitProcess`'s cleanup threw over an output
   file receive-pack still held, and discarded git's answer.
7. **#360 was verified live at 14:55** on main `ccca9e6`.
   - The boot was healthy, with 0 WARN or ERROR lines.
   - No `.p*` path had ever been committed, and nothing under `.ps` was tracked.
   - The 36 MCP answers were unchanged.

   A real push through the gate was not exercised.

## Pages this source updated

The four linked above: the two decisions' Outcomes (#360's and #370's live checks) and the two
concepts.
