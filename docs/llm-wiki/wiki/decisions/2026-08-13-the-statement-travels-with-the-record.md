---
type: decision
project: programmers-tracker
tags: [records, vault, mcp, courtesy, protocol]
author: BrokenFinger98
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-13-the-map-becomes-a-workspace.md]
---

# The problem statement is stored with the record, in the private repository only

## Context

Every MCP tool returned metadata and gradings and none of them could say **what the problem
asked**. An AI reading `get_problem` knew testcase 3 failed, how long it took, which language and
how many runs — and had no idea what was wanted. The vault had the same hole from the other side:
a problem note was a history of attempts against a question the note did not contain.

The owner raised it by showing BaekjoonHub, which copies statements into the user's repository and
has for years.

## Options considered

1. **Leave it out.** Rejected once the size of the gap was named: it is the largest missing input
   on the MCP surface, and no other field can stand in for it.
2. **Store the raw HTML fragment in a `.md`.** Markdown carries inline HTML and Obsidian renders
   it, so this is a real option and the cheapest one. Rejected: the file stops being editable, and
   the point of the vault is that the reader owns what is in it.
3. **Convert to Markdown and store it.** Chosen.

## Decision

The statement is converted to Markdown and written to `problems/<lesson>-<slug>/statement.md`,
**write-if-absent**, and the README embeds it with `![[statement]]`.

Three properties, each of which was the reason for a different part of the shape:

- **It rides on a fetch we already make.** `CodeAttachment` downloads the problem page to read
  the saved code; the statement is on that same page, so `CodeFetch.Fetched` now carries both and
  Programmers sees no additional request. Development-rules §9.3 asks exactly this of every
  request we make.
- **A file of its own, written once.** `README.md` is regenerated on every grading. A statement
  living inside it would have to be re-fetched every time to survive — which is the same trap
  that blocked design §6.9's retrospectives, one file over. The embed keeps the reader's
  experience single-page while the regeneration boundary stays clean: the server owns
  `README.md`, the reader owns `notes.md`, and `statement.md` is written once and then left.
- **Private repository only.** The record repository is created `private` and re-verified private
  before anything is wired ([[decisions/2026-08-13-the-server-prepares-the-repository]]), and
  development-rules §7.3 already forbids Programmers' own example values in *this* repository's
  fixtures. A statement never enters this repository at all — the parser's fixture is
  structurally verbatim with every word invented.

## Rationale

**On the precedent, including the part I got wrong.** My first objection was that Baekjoon's
statements have mixed provenance while Programmers' are Grepp's own, so the precedent would not
transfer. The owner then produced a *Programmers* example — lesson 276036, full statement, in a
**public** repository, years old. Same rights holder, same practice, and public where ours is
private. The objection was wrong and withdrawn.

What survives is narrower and worth keeping in view: no complaint is evidence about risk, not
about rights. Which is why the shape above is the conservative version of a practice that already
exists at scale.

**On parsing it with jsoup.** The two parsers beside this one read a single attribute off a
single tag, which regex does honestly. A nested document is not that, and
[[decisions/2026-08-13-a-floor-per-package-and-a-reason-per-exception]] was written the same day
about matching nested markup by hand. jsoup also never throws on malformed input, which is the
posture development-rules §2 asks of everything that reads Programmers.

**On converting rather than copying.** Programmers renders the author's Markdown into HTML and
leaves `class="markdown"` saying so, which is what makes converting it back well-defined instead
of a guess. Measured across five lessons (120802, 12916, 151136, 17676, 42894), the tag set is
twenty tags with no nested `<div>` — and the absent `<div>` is what makes the region's boundary
unambiguous.

## Accepted costs

- **What is not recognised is emitted as raw HTML.** Nested lists inside list items are the known
  case: flattening them would change the content, so they stay as markup that Obsidian renders
  correctly and a text editor shows verbatim. Degrading to "looks slightly raw" beats losing a
  paragraph (dev rules §2.3).
- **Images stay on Programmers' CDN.** A vault read offline shows broken images. Downloading them
  would mean copying binaries out of somebody else's asset host, which is a larger step than
  copying text a browser already displays, and it is not needed to read the problem.
- **`statement.md` never updates.** A problem Programmers rewords keeps the wording it had when
  first captured. Deliberate — the alternative overwrites a file the reader may have annotated,
  and the record is a record of what *was* asked at the time it was solved.
- **A new runtime dependency.** jsoup, for one feature. The alternative was writing a tolerant
  HTML parser, which is a worse version of jsoup.

## Outcome

Implemented in #277 (issue #275). The converter is verified against the five captured pages as
well as the scrubbed fixture; the write path is unit-tested, and the end-to-end proof is the
owner's next grading, which is the only thing that exercises fetch → parse → write together.
