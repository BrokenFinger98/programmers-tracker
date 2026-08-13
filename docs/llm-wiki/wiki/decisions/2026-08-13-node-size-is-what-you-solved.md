---
type: decision
project: programmers-tracker
tags: [vault, obsidian, measurement, failed-attempts]
author: BrokenFinger98
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-13-the-map-that-linked-to-nothing.md, raw/sessions/2026-08-13-the-tally-that-counted-runs.md]
---

# The tag map is sized by what you solved, not by what the catalog groups

Reverses the #231 amendment to
[[decisions/2026-08-12-the-server-counts-and-names-nothing]]. That decision's core — the server
counts and names nothing — is untouched and still holds.

## Context

#229 shipped a note per catalogued tag, linked from the problems that carry it. The live vault
showed **81 of 83 tags isolated**, so #231 joined tags that share a catalogued problem: 255 pairs,
zero tags with no neighbour, highest degree 27.

The map then had shape. The owner opened it on 2026-08-13 and asked two questions that the shape
could not answer: *why is node size the catalog and not what I solved*, and *why can I not click a
type and reach the problems I did*.

## Options considered

**A. Keep the edges.** As problems accumulate, problem→tag links dilute the catalog edges.
Measured, that is a long wait: `implementation` carries 27 catalog neighbours against 2 solved
problems, so it takes **27 solved implementation problems** before the owner's own work merely
matches the catalog's contribution to that node's size. Until then the biggest node is the
catalog's, on every user's map, identically.

**B. Make the edges configurable.** Both pictures available, one more setting. Rejected as
encoding "we have not decided" into the product — and the two pictures answer different
questions, only one of which this vault is for.

**C. Take the edges out and link the problems instead.** Node size becomes the number of problems
worked on under that tag. Untouched tags go back to being isolated.

## Decision

**C.** Tags link only to the problems whose records raised their counts, and never to another tag.
The note names them, split into passed and attempted-without-a-pass.

**"Never met" moves from isolation to colour.** An Obsidian graph colour group on `["solved":0]`
marks every untouched tag without touching node size. The recipe ships in
`template/ps-records/README.md`; the server writes the frontmatter it reads and stops there.

## Rationale

Obsidian sizes a graph node by how many notes link to it and **cannot read frontmatter**. So every
link the server writes is a claim about magnitude whether it means to be one or not. Measured on
the live vault:

| tag | links | of which are the owner's problems |
|---|---|---|
| `implementation` | 29 | **2 (7%)** |
| `arithmetic` | 7 | **2 (29%)** |

#231 was solving the wrong problem. The dust cloud was not a defect — it was an accurate picture
of four solved problems. Replacing it with 510 catalog edges made the map *look* informative while
removing both things it was for: what you have solved a lot of, and what you have never met.

And colour does the second job better than isolation ever did. Isolation stops meaning anything
the moment the vault matures; `["solved":0]` keeps working at any size, and it costs the map
nothing.

Naming the problems is aggregation, not interpretation — the note lists records that exist, in the
catalog's order, ranked by nothing. §3.4 of the spec had refused on the grounds that a generated
list is a second copy that can disagree with the backlink pane. It cannot: the note is overwritten
whole from the records on every pass, exactly as `solved: 2` is.

## Accepted costs

- **A new vault is a field of isolated dots again**, and now the README has to explain that rather
  than the graph showing it. Traded knowingly: a picture that is honest and needs a caption beats
  one that reads well and says nothing about the reader.
- **Two reversals in two days on the same feature.** #231 was decided against a screenshot; this
  is decided against the same screenshot plus the link counts underneath it. The counts are what
  was missing the first time.
- The co-occurrence measurement (255 pairs, degree 27) is now unused. It was correct and it
  answered a question nobody needed answered.

## Outcome

Implemented in #241 — `TagCoverage.relatedBy` and `TagCount.related` removed, `TagCount.touched`
added, `RecordLayout.problemNoteLink` owns the link the same way `tagNoteLink` does (#233), and
the test that resolves every emitted link against the files on disk now covers both kinds.
