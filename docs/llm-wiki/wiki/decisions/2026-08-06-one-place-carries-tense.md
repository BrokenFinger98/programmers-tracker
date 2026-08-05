---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [documentation, readme, honesty, drift, public-repo]
created: 2026-08-06
updated: 2026-08-06
sources: [decisions/2026-08-04-english-only-artifacts, concepts/assumption-vs-measurement]
---

# The README states build status in exactly one place

Date: 2026-08-06 · Status: accepted · Issue: #47

## Context

The README described the finished product in the present tense and carried a one-line
"under development" banner. Nine claims were false against `src/main/kotlin`: MCP exposure
in the opening sentence and again in the comparison table, saved failing code and
attempt-to-attempt diffs (`CodeFetcher`, `CodeArtifacts` and `ProblemReadme` are all written
and none is wired), the sensor and MCP boxes of the architecture diagram, all seven "what
you get to know" bullets, a catalog fetch that does not exist, `.claude/commands/` after the
directory became `.claude/skills/`, and a structure block saying the server creates the
record repository four sections after *Get started* has the user create it.

The banner was not the failure. **The failure is that status was asserted in nine places, so
keeping the document true required an audit rather than an edit** — and an audit that nobody
runs is how every one of those nine drifted in the first place. This is the same defect class
as #44 and #48: a document asserting a property the code does not have, which CLAUDE.md names
as the worst outcome available to this project.

The competing pressure is that the design is the strongest thing in the repository and the
main reason it is worth showing. Deleting the ambition would fix the tense and destroy the
content.

## Options considered

- **Caveat each claim in place** — rejected. It ages badly (every merge edits several
  sections), reads as apology, and leaves the same nine-places problem it was meant to fix.
- **Cut everything unbuilt** — rejected. The design work is the point of the repository;
  a stranger reading only the built half sees a WebSocket logger.
- **Split into README (built) and DESIGN (aspirational)** — rejected. Two documents drift
  against each other exactly as nine sections did, and the interesting claim is precisely
  the relationship between the two halves.
- **One status table; every other section written in design tense (chosen).**

## Decision

1. **`README.md` states what is implemented in exactly one section — *What works today*.**
   Each row is a capability with a state (`built` / `designed · §n`). Landing a feature flips
   one cell. Nothing else in the file may assert build status.
2. **Everything after that table describes the design**, and says so where a reader could
   mistake it: the architecture diagram is captioned as the design, the comparison table
   compares what the two tools are built to do, and the analysis bullets are titled *what the
   record is designed to tell you*. None of them needs editing when a feature lands.
3. **The design's section numbers are cited in the table**, so an unbuilt row points at where
   it was specified rather than merely being absent.
4. **The factual errors are fixed outright** — `.claude/skills/`, the user creating the record
   repository, and the request list under *Principles toward Programmers*, which now says the
   server sends exactly one kind of request today (the channel subscription) and names the
   two the design adds.
5. `CONTRIBUTING.md` records rule 1 as a contributor obligation, alongside the corrected
   **JDK 25** (it said 21 — a contributor installing a 21 fails the build before writing a
   line) and the #50 warning that renaming a CI job makes `main` unmergeable.

## Rationale

- The test of the shape is #46, the MCP read slice, which is queued directly behind this
  change: it flips one cell from `designed · §7` to `built`. Under the old shape it would
  have had to edit the opening sentence, the comparison table, the architecture diagram and
  the analysis bullets, and would have had no way to tell whether it had found them all.
- An honest "here is what works, here is what is designed" reads better to a reviewer than an
  aspirational feature list, because it demonstrates the judgement the wiki exists to show.
  The ambition is not reduced by dating it.
- Status stated once can be *checked* once. Nine independent claims cannot be reviewed; one
  table can.

## Accepted costs

- The rule is a convention, not a mechanism. Nothing stops a future edit from writing "the
  MCP server exposes…" into the opening sentence; only review does.
  [[decisions/2026-08-06-markdown-paths-must-exist]] mechanises the paths half and explicitly
  refuses to fake the semantic half with a keyword list.
- `docs/bootstrap.md` had two bullets phrased as *the README claims X and X is false*. They
  now point at the design instead of at the README, which is one more place that had to move
  in step — the coupling this decision reduces but does not remove.
- The table is coarse. A capability that is half-built has no honest cell, and the first one
  that appears will need either a third state or a split row.

## Outcome

Recorded 2026-08-06 with #47. Related: [[decisions/2026-08-06-markdown-paths-must-exist]] ·
[[concepts/assumption-vs-measurement]].
