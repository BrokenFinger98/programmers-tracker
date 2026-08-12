# A Tag Map in the Vault

Written: 2026-08-12
Issue: #229
Status: approved in conversation with the owner; not yet implemented

---

## 1. The question

`ps-records` opens as an Obsidian vault. The owner wants its **graph** to answer one thing at a
glance: *which problem types have I covered, and which have I not met at all?*

They said the caveat themselves — solving many of a type does not mean being good at it. This
design takes that seriously in both directions, and the second direction is the harder one.

## 2. Why the obvious version does not work

Obsidian's graph draws **links**. A problem's `README.md` today carries inline `#dp` hashtags,
which feed the tag pane and search but cannot carry a denominator, and are not graph nodes unless
the graph's tag option is enabled.

The deeper problem is that **a graph of your own records can only show what you touched.** A type
you have never met is not a faint node. It is absent, and absence is exactly what the owner wants
to see.

### The measurement that shapes the design

| | |
|---|---|
| catalogued problems | 689 |
| tag-vocab entries | 229 |
| **distinct tags the catalog actually uses** | **83** |
| tags carried by ≤ 2 problems | **37 of 83** |
| `implementation` | 379 problems — more than half the catalog |

The distribution is severe enough that a bare node count lies in both directions. **Not having
solved `tsp` is not a weakness** — the catalog holds one such problem. **Having solved none of
`dp`'s 38 is a real gap.** A graph without denominators cannot tell those apart, and would
present them identically as isolated nodes.

## 3. What the server writes

### 3.1 One note per catalog tag

`tags/<tag>.md`, one per tag **the shipped catalog actually uses** — 83 today. The set is derived
from the catalog at runtime, never a constant in code: the catalog is a snapshot that could be
replaced, and a hardcoded 83 would survive the replacement and be wrong.

```markdown
---
tag: dp
catalogTotal: 38      # from the shipped catalog snapshot; never changes
attempted: 5          # problems carrying this tag with any record
solved: 3             # of those, the ones with a PASS
---

# dp

Met 5 of 38, passed 3.
```

`attempted` and `solved` are separate because *never tried* and *tried and failed* are different
facts — the same distinction `list_problems` exists to make.

**Notes are created for tags with no records at all.** That is the point of the whole design: a
`dp: 0 / 38` note is an isolated node in the graph, and the isolation is the finding.

### 3.2 Problems link to their tags

`README.md` gains a link line:

```markdown
# 두 수의 곱 구하기

#programmers #Lv0 #arithmetic

Tags: [[tags/arithmetic]]
```

**The inline hashtags stay.** The two mechanisms serve different features — hashtags drive the
tag pane and search, wikilinks drive the graph and backlinks — and removing the hashtags would
break something that works today. If the graph's tag option is enabled a name may appear as two
nodes; that is a viewer setting the owner controls, not the server's call.

### 3.3 The tag note does not list its problems

Obsidian's backlink pane already answers *"which problems link here"*. Generating that list would
create a second copy of something the records already say, and a second copy is a thing that can
disagree with the first.

### 3.4 When it is written

- **At startup** — write every tag note, not only the missing ones. Startup is also when
  reconciliation recovers records left by a crash, and those records change counts; refreshing
  only what is absent would leave a note disagreeing with the log until the next grading happened
  to touch that tag. Writing identical bytes is not a change, so git sees nothing for the
  untouched ones.
- **On each record** — rewrite only the tag notes of the problem just recorded, normally one or
  two. Never the whole set.

**The counts are recomputed from the submission log, never incremented.** A held counter is a
second authority that a reconciliation or a restart can put out of step, which is the defect
family this repository spent 2026-08-12 removing. Counting 22 records — or thousands — is free.

## 4. The boundary: counts yes, meaning no

This is the part that settles a standing conflict, and it gets its own ADR.

The server may **aggregate**. `stats` counts, `review_queue` computes a date. The constitution's
prohibition is on the server **concluding**:

> ❌ Rule-based analyzers inside the server — interpretation is the AI's job. The server collects
> and aggregates, no further.

So the tag note states three numbers and stops. What it never does, and why:

| not done | why |
|---|---|
| a verdict on a ratio — `dp: 8% — weak` | "below what percent is weak" is a number the server cannot possess |
| tags ordered by neglect | the ordering *is* the judgement. The notes are files; the filesystem orders them |
| a stored threshold flag | design §5.5's own example query referenced a `slowFlag`. **That is the real line** — the server deciding what counts as slow and writing the decision into a file |
| "start here" | interpretation, and the AI's job |

**`_weakness.md` is not built.** Its name is already a conclusion, and a single file that points
at a weakness is a rule-based analyzer whatever it contains. `tags/` answers the same question
with structure: density and isolation are visible, and what counts as a gap is the reader's.

This is the same line [[decisions/2026-08-10-scheduling-is-not-diagnosis]] drew for
`review_queue`, and it carries the same cost — every fact behind a number ships with it, so the
reader can disagree.

## 5. Failure and edges

**Obsidian is optional.** The notes are plain Markdown; frontmatter renders as a table on GitHub
and the body as text. Nothing here depends on a plugin. Dataview is not used — it is a
third-party plugin, it is not installed on the owner's vault, and requiring an install would be
friction for a publicly distributed tool. A viewer that wants live tables can use Obsidian's
built-in Bases over the same frontmatter; this design neither needs nor blocks it.

**A problem with no catalog metadata** — one published after the snapshot — carries no tags, so
it links to nothing and no tag note is invented. Absent, not defaulted, exactly as `level` is
today.

**A tag not in the catalog** should not occur, since record tags come from the catalog join. If
it ever does, the note is created with `catalogTotal` **omitted**. `catalogTotal: 0` beside
`solved: 1` reads as broken, and 0 would be a guess rather than a measurement.

**A failed write costs no record.** The record is written first and the tag notes after; a
failure is logged and does not propagate — the posture `writeReadme` and `writeRunner` already
take.

**Human edits vanish.** A tag note is overwritten on the next record carrying that tag, the same
contract `README.md` has. Prose belongs in `notes.md`, which the server never touches.

**`tags/` is committed.** It is derived, and so is `README.md`; the daily backup's `git add --all`
carries it.

## 6. Testing

**Unit, zero mocks** — a pure calculator: `(records snapshot, catalog) → per-tag (catalogTotal,
attempted, solved)`. The boundaries are the whole of it:

- a problem with two tags counts under **both**
- records but no pass raises `attempted` and not `solved`
- **a tag with no records at all yields `0 / 0 / 38`** — this case is the graph's hole and must
  be pinned
- a record with no tags contributes nowhere

**Layer** — the writer: a second boot over an unchanged log produces byte-identical files (so
git sees nothing); one record rewrites only that problem's tags; a throwing write does not cost
the record. **A test drives the set from a two-tag stub catalog rather than the shipped one**, so
it pins the behaviour and not the number 83.

**`RecordRepositoryTemplateTest` must be fed.** It enforces that every file the template README
names is one the server actually writes — the guard that caught the dashboard drift at #96 and
the design drift at #227. Adding `tags/` means adding it to the template's structure block *and*
to the test's `written()` set. Skipping that drifts in the opposite direction: the server writes
something the user's own README never mentions.

## 7. Deliberately not built

| | why |
|---|---|
| `_weakness.md` | §4 — the name is a conclusion |
| `_dashboard.md` | the per-problem frontmatter already feeds Obsidian's built-in Bases |
| `_review.md` | `review_queue` answers it, with the facts that set each date |
| `_warmup.md` | no one has asked for it |
| `_exam.md` | #146 is undecided |
| a note per catalogued problem | 689 ghost nodes, and the record repository would become a copy of the catalog. It holds *your records* |
| a minimum-problems threshold for which tags get a note | the threshold would be a judgement, and "why 5" has no answer. All 83 get one; the graph's own filters are the viewer's tool |

## 8. Accepted costs

- **83 files appear in the record repository on first boot.** Visible, one-time, and committed.
  A user who wants fewer has no switch; the alternative was a threshold nobody can justify.
- **The graph gains 37 nodes carrying two problems or fewer.** They are dust, and hiding them is
  a viewer setting rather than something the server decides.
- **`implementation` will dominate any view** — 379 of 689 problems. The tag vocabulary is
  solved.ac's and we do not invent our own (`2026-08-04-solved-ac-tag-vocabulary`), so this is
  inherited, not chosen.
- **`attempted`/`solved` are stored, and stored counts are a second surface that can disagree
  with the log.** Mitigated by recomputing rather than incrementing. Considered and rejected:
  storing only `catalogTotal` and letting the backlink pane supply the numerator — it would make
  the note un-self-describing for anyone not using Obsidian, and the owner's call was that the
  files should stand alone.
- **A tag map says nothing about depth.** Ten `implementation` passes at Lv0 and ten at Lv3 count
  alike. Level is on each problem's frontmatter and not aggregated here; anything that combined
  them would be assigning meaning.
