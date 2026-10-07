---
type: source
project: programmers-tracker
tags: [security, links, mcp, prompt, client-compatibility, measurement, failed-attempts]
created: 2026-10-08
updated: 2026-10-08
sources: [raw/sessions/2026-10-07-the-readers-that-followed-links.md]
---

# 2026-10-07 (evening) session summary — the readers that followed links

## Key claims

1. **The audit for #354 found two readers the issue had not named.**
   - The statement inlined into the pushed README, which was the worst of the six.
   - `examples.json`, read into a pushed runner.

   All six now read through `ProblemFiles`. Writers following links (#361) and a linked
   `.gitignore` staging the push token (#360) were measured in a scratch copy and filed.
2. **Two review rounds changed the fix.**
   - Refusals had been silent; they now warn with the path and the reason.
   - The test log capture had missed throwables, so a mutant that logged the link target passed.
   - The `/watch` token had been misplaced in the records repository in seven places.
   - Probes showed what the bound cannot stop: a hard link, and a directory swapped mid-read.
3. **#354 was verified live at 23:03.** The 36 snapshot files of MCP reads were identical before and
   after the deploy. The boot logged no refusal, and the record repository was untouched at 7e144fa.
4. **The facts behind #364 came from the client's code and logs, not from the specification.** Claude
   Code 2.1.285 splits prompt arguments on whitespace by position. Its modern codec drops every
   prompt when `ttlMs` or `cacheScope` is missing. All 27 local connections ran the modern revision.
   The first review of the prompt text corrected three plan hypotheses: "labels", the empty answer,
   and unquoted values.
5. **A repository rule had been broken by 117 of 180 commits.** The commit skill forbids AI trailers.
   From 23:23 on, no trailer was added.

## Pages this source updated

[[decisions/2026-10-07-no-reader-follows-a-link-out-of-problems]] ·
[[decisions/2026-10-07-repair-steps-are-served-not-judged]] ·
[[decisions/2026-10-07-exam-prep-asks-in-the-open]] · [[entities/claude-code-mcp-client]] ·
[[concepts/assumption-vs-measurement]] · [[concepts/orchestrated-implementation]]
