---
type: decision
project: programmers-tracker
tags: [boundary, obsidian, aggregation, constitution, vault]
author: BrokenFinger98
created: 2026-08-12
updated: 2026-08-13
sources: [raw/sessions/2026-08-12-clean-slate-verification.md]
---

# The Server Counts the Map and Names Nothing on It

## Context

Design §5.5 listed a server-generated `_weakness.md`. `CLAUDE.md` forbids **rule-based analyzers
inside the server**. The two founding documents have contradicted each other since the
prohibition was written, and #227 amended the design to say so without settling it.

The question became live because the owner asked for something concrete: an Obsidian **graph**
showing which problem types they have covered and which they have not met. Answering it needs
notes the graph can draw, and notes carrying numbers — which is exactly the shape the conflict is
about.

## Options considered

**A — Build `_weakness.md` as designed.** A note that ranks tags by how badly they are going.
Rejected: the name is a conclusion before the file has any content, and a single file that points
at a weakness is a rule-based analyzer whatever its implementation.

**B — Build nothing; the frontmatter already works.** Obsidian's built-in Bases reads each
problem's `README.md` frontmatter today, so a user can build any table they like. Rejected on the
owner's actual requirement: **a view over your own records cannot show a type you never met.** The
gap is the thing they wanted to see, and it is invisible by construction.

**C — A note per catalog tag, carrying counts and nothing else.**

## Decision

**C.** `tags/<tag>.md` for each of the 83 tags the shipped catalog uses, carrying `catalogTotal`,
`attempted` and `solved`. Problem READMEs link to their tags so the graph has edges. Tags with no
records get a note too — an isolated node is the finding.

**And the boundary, stated so it can be checked:** the server may count; it may not name. No
verdict on a ratio, no ordering by neglect, no stored threshold, no "start here".

## Rationale

**The prohibition is on concluding, not on counting.** `stats` counts and `review_queue` computes
a date; neither was ever the thing forbidden. Reading the rule as "the server may only store"
would retire both. What it forbids is the server deciding what the numbers *mean*.

**The denominator is what makes the map honest, and it is a fact rather than a judgement.** The
catalog uses 83 tags across 689 problems, and 37 of those tags carry two problems or fewer while
`implementation` carries 379. Without `catalogTotal`, an isolated `tsp` node (one problem in the
whole catalog) and an isolated `dp` node (38) look identical — and only one of them is a gap. The
number comes from a snapshot we already ship and never changes, so stating it commits the server
to no opinion at all.

**Where the line actually is, and it is not where the name suggested.** Design §5.5's own example
query referenced a `slowFlag` in frontmatter. *That* is the violation — the server deciding what
counts as slow and writing the decision into a file a reader will trust. A count is a
measurement; a flag is a verdict. `_weakness.md` was never dangerous because it aggregated. It
was dangerous because it named.

**Structure answers the question a conclusion would have.** Density and isolation are visible in
the graph without any file saying which is bad. The reader — or an AI over MCP, which is whose
job the constitution says it is — decides what counts as a gap.

Same line as [[decisions/2026-08-10-scheduling-is-not-diagnosis]], which let `review_queue`
compute a date but not a diagnosis, and carried the same price: every fact behind the number
ships beside it.

## Accepted costs

- **The server now writes 83 files into the record repository on first boot.** One-time and
  visible. A user who wants fewer has no switch, because the alternative was a
  minimum-problems threshold and "why 5" has no answer that is not a judgement.
- **Counts are stored, and a stored count is a second surface that can disagree with the log.**
  Mitigated by recomputing from the submission log rather than incrementing — a held counter is
  what a reconciliation or a restart puts out of step, which is the defect family 2026-08-12
  spent the day removing. The alternative considered was storing only `catalogTotal` and letting
  Obsidian's backlink pane supply the numerator; it was rejected because the files must stand
  alone for a user who does not open Obsidian at all.
- **The map is flat.** Ten `implementation` passes at Lv0 count the same as ten at Lv3. Level
  lives on each problem and is deliberately not folded in here, because anything combining them
  into a single "coverage" number would be assigning meaning.
- **`implementation` will dominate every view** at 379 of 689. Inherited from solved.ac's
  vocabulary, which [[decisions/2026-08-04-solved-ac-tag-vocabulary]] chose precisely so that we
  do not invent our own taxonomy. The cost of not inventing one is living with its shape.
- **A reader can still draw a wrong conclusion**, and nothing here prevents it. Disclosure is not
  protection — the same limit `2026-08-11-a-hole-in-the-record-is-reported-not-filled` accepted.

## Outcome

Spec: `docs/superpowers/specs/2026-08-12-tag-map-vault-design.md`. Not yet implemented at the
time of writing.

`_weakness.md` is closed rather than deferred: it will not be built under that name or that
shape, and design §5.5's amendment now points here instead of leaving the conflict open.

## Amended 2026-08-13 (#231): the map needed edges between tags, not only into them

> ⚠️ **Superseded 2026-08-13 by [[decisions/2026-08-13-node-size-is-what-you-solved]] (#241).**
> Kept in full because the reasoning below is intact and the thing it missed is the lesson:
> Obsidian sizes a node by its link count, so 510 catalog edges made every map's biggest node the
> catalog's rather than the reader's. The core decision of this page — the server counts and names
> nothing — is untouched.


The first version shipped and the live vault showed **81 of 83 tags isolated.** The decision's
own argument had been that an isolated node is the finding — and it holds only **when isolation
is rare.** With four problems recorded, near everything was isolated and isolation carried
nothing at all. The premise was about a mature record set and was never stated as one.

Tags now link to the tags they share a catalogued problem with. Measured before writing any
code: **255 pairs over 83 tags, zero tags co-occurring with nothing, highest degree 27, average
near 6.** So the map has shape before a single problem is solved, and the hairball this might
have produced does not exist.

**The boundary is unchanged, and the measurement is what keeps it that way.** Co-occurrence is a
count of what solved.ac already tagged — no threshold (the numbers say none is needed, and a
cutoff would be a judgement), no ordering by strength (an ordering is a claim), no prerequisite
graph (design §6.10 orders *learning*, which is judgement, and inventing a taxonomy is forbidden
outright).

Worth recording as its own lesson: **the decision was right and its unstated assumption was
wrong.** Nothing in the reasoning was false; it simply assumed a vault with history. The check
that caught it was opening the thing and looking at it, which is the same check that has caught
most of this repository's defects.

## Tested again 2026-08-13 (#250): naming a state is not naming a weakness

The owner asked for attempted-but-not-passed tags in red. Obsidian's graph colours on a *search
query*, not an expression, so the counts alone forced `-["attempted":0] ["solved":0]` — right, and
not a thing anyone should have to derive.

The note now carries `status: untouched | attempted | passed`, and the line this page draws is
what decided the shape:

- **Allowed**, and this is it: a word that restates `attempted` and `solved`. It says where you
  stand, adds nothing, ranks nothing, and is derived on read so it cannot disagree with the two
  numbers it summarises.
- **Still refused**: any suggestion that `attempted` is worse than `untouched`, an order over the
  tags, a threshold, a colour chosen for you. The reader picks which state deserves red — the
  server does not know that a half-finished type matters more than one never met, and for
  someone deliberately deferring a topic it does not.

`ProblemStatus` was reused rather than a second vocabulary invented; `list_problems` has answered
in those three words since #100. Two views of one thing needing two vocabularies is the
`submissionCount`/`attempts` confusion #237 had to unpick.

