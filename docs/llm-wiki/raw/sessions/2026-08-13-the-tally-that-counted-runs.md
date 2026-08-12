# 2026-08-13 — the tally that counted runs

Raw session record. Immutable (wiki schema §1).

The night after #229 shipped. #232 merged, the vault was verified against the running server,
and then two of the server's own tools were asked the same question and gave different answers.

---

## First: the map, verified against the thing itself

#232 merged, the image rebuilt, the container restarted. Then the questions a test cannot ask:

| question | answer |
|---|---|
| tag notes on disk | **83** |
| wikilinks in the vault | **514** — 510 tag→tag over 255 pairs, 4 problem→tag |
| links resolving to nothing | **0** |
| tag notes disagreeing with the records | **0 of 83** |

The last row is worth the most. The counts were recomputed in Python straight from
`log/submissions.jsonl` and `catalog.json` — an independent implementation of `TagCoverage` —
and compared against the files Kotlin wrote. Not one disagreed.

The 514 also matched a prediction made before the deploy (510 + 4), which is the cheapest kind
of confidence: the arithmetic was done first and the world agreed afterwards.

## The count in #233 was overstated, and the correction is smaller than the claim

Written the night before: *43 of 83 tag links resolved to nothing.* Measured after: **43 of 83
tags** could not be linked to, which is not the same sentence. The vault held **4** links, both
tags unslugged, so **nothing on disk was broken**. #232 would have made it 178 dangling out of
510.

Latent, not live. The defect was real and the fix was right; the number described a vault that
did not exist yet. Corrected in `progress.md` and the wiki before merging, since the owner was
going to open that vault in the morning and be told what they were looking at.

## Then: `list_problems` said 8 and `stats` said 15

Same server, same log, same moment, one problem:

```
list_problems(status=passed)  →  181952 … "attempts": 8
stats(groupBy=problem)        →  181952 … "count":   15
```

Nothing had asked the two tools the same question before.

### What the tally was actually counting

```
stats(groupBy=verdict)  →  PASS 11 · COMPILE_ERROR 7 · RUNTIME_ERROR 2 · WRONG 1 · (no key) 1
```

Split by action, over the same 22 records:

| | PASS | WRONG | COMPILE_ERROR | RUNTIME_ERROR | unresolved |
|---|---|---|---|---|---|
| **submit** (11) | 10 | 1 | — | — | — |
| **run** (11) | 1 | — | 7 | 2 | 1 |

**Every compile error and every runtime error in that tally is a run.** The owner has made 11
submissions and passed 10 of them. An AI reading `stats` sees someone who cannot get code to
compile — and the MCP layer exists precisely so the AI interprets and the server does not, which
only works if the counts are true.

### The rule existed; four places kept it and the fifth did not

- `ReviewQueue.passed()` → `action == SUBMIT && verdict == PASS`
- `SlowPasses.passed()` → the same line
- the tag map's caller → `filter { it.action == SUBMIT }`
- `CatalogBrowse` → live proof: `attempts` 8, not 15
- **`SubmissionTally.of`** → counted whatever it was handed, and `RecordQuery.tally` hands it
  `history()`, which is runs and submits both

Its own KDoc said "Counts submissions per bucket". `docs/mcp.md` said "counts submissions per
problem". *A run is not an attempt* is design §5.1, and `ProblemReadme` has always obeyed it —
runs get no row and are counted separately as `runCount`.

### No test pinned it, in either direction

Every tally test builds records with the fixture's default action, which is `SUBMIT`. So the
suite never handed the calculator a run, and counting them was not a decision anyone made — it
was what reading `history()` happened to do. The whole suite stayed green after the fix, which
says the same thing from the other side: nothing depended on it.

**A rule kept by convention in four places is enforced in none.** The fix puts it inside the
calculator, where the other four keep theirs, and adds the test that hands it both kinds.

## What found each thing tonight

| finding | what found it |
|---|---|
| the map's links now resolve | walking the real vault and resolving every link against the directory |
| the tag counts are right | reimplementing the calculator in another language and diffing |
| 43 of 83 was the wrong denominator | counting the links that actually exist rather than the tags |
| the tally counted runs | asking two tools the same question and comparing their answers |

Not one of them came from the test suite, and the suite was green throughout — the same sentence
as the night before, and the night before that. The pattern holds: **every check agrees with the
code, because the same author wrote both.** What breaks them is an outside reference. Tonight the
outside references were the filesystem, a second implementation, and the server's other tool.

## Also filed, not fixed

`reconcile()` is `git add --all`, so it commits whatever is in the vault. On the owner's
repository four commits carry Obsidian's editor state, and one of them — the 23:00 backup —
contains *nothing else*, under a message that says it reconciled records (#234). Left for the
owner: narrowing the staging scope trades a visible annoyance for the invisible failure the net
exists to prevent, and that is not a 1am call.

## Verified in passing

The daily backup fired on its own for the first time under observation: `14:00:29Z` =
**23:00 KST**, exactly `TRACKER_BACKUP_AT`, reconciled, committed, pushed, and said so. A
scheduled job is another thing a unit test cannot prove.
