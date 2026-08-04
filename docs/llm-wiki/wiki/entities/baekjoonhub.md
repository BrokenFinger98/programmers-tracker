---
type: entity
project: programmers-tracker
tags: [baekjoonhub, prior-art]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# BaekjoonHub

A Chrome extension that auto-pushes solutions to GitHub. The prior tool this project replaces.

## Structural limitation

```js
// scripts/programmers/programmers.js:46
else if (getSolvedResult().includes('정답')) {   // ← 2 s polling, only on '정답' (correct)
```

It polls the result modal every 2 seconds and scrapes the CodeMirror content **only on
"정답" (correct)**. Wrong answers, timeouts, and attempt counts therefore
**structurally cannot be recorded.**

Results are also scraped from the DOM (`td.result.passed`), carrying less information
than the original stream.

To be removed once this project is complete.

→ [[syntheses/protocol-reverse-engineering]]
