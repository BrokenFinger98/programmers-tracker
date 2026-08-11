---
type: source
project: programmers-tracker
tags: [catalog, storage, licensing, runners, courtesy]
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-06-catalog-runners-and-the-record-repository.md]
---

# 2026-08-06 session summary

Back-filled 2026-08-11.

## Key claims

1. The catalog is **our labels over their identifiers** — id, title, level, tags — not their
   content, which is what kept it on the right side of development-rules §9.3. Two nearer-to-
   crawling candidates were dropped on the owner's instruction.
2. The owner overruled a YAGNI deferral of the catalog on sequencing grounds: the labelling is
   embarrassingly parallel, does not need the main context, and the artifact is once-ever.
3. solved.ac publishes **229** tags; the design document had said 180. The document was
   corrected to the measurement.
4. Programmers has two problem shapes — `main`-style (stdin I/O) and `solution`-function — and
   the owner identified this from problems rather than from code, before any runner existed.
5. The run console publishes each example's input and expected value, which is where
   `examples.json` and every generated runner get their data.
6. Two instances must not share one record repository; a bind-mounted filesystem cannot be
   trusted to hold a file lock, hence lock **plus** change-based heartbeat.
7. The SSH push path had never once run — `openssh-client` was missing from the image.
8. MCP hands over data and cannot make a client reason a particular way. Recorded unresolved.

## Pages this source updated

[[decisions/2026-08-06-shipped-problem-catalog]] · [[decisions/2026-08-06-record-repository-lock]] ·
[[decisions/2026-08-07-server-generated-runners]] · [[decisions/2026-08-06-wire-git-into-the-pipeline]]
