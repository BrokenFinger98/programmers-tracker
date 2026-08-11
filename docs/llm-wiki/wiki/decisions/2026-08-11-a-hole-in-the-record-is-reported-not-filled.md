---
type: decision
project: programmers-tracker
tags: [records, recovery, mcp, analysis, failure-taxonomy]
author: BrokenFinger98
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-11-capture-defects-found-by-solving.md, decisions/2026-08-08-run-raw-sessions, decisions/2026-08-05-failure-taxonomy]
---

# A hole in the record is reported, not filled

## Context

`<record-repo>/.ps/raw/orphans/` holds frames that belong to no grading — kept rather than
dropped since #107, because a missed `start` should cost the verdict and not the evidence.

Two files sit there on the author's machine, both left by defects fixed on 2026-08-11 (#152
truncated a failing run at its first error, #154 did the same to a cached-result submit):

| file | contents |
|---|---|
| `181946.jsonl` | a failing run, and a complete submit grading minus its `start` — 4 testcases through to `finish` |
| `120802.jsonl` | two failing runs, and a complete submit minus its `start` — 18 testcases through to `finish` |

The records those gradings produced exist and are **wrong, not missing**:

```
181946  submit 3  UNKNOWN  0/0     ← the frames say 4/4
120802  submit 2  UNKNOWN  0/0     ← the frames say 18/18
```

Per [[decisions/2026-08-05-failure-taxonomy]] that is the worse half — a record that looks
measured. The temptation to repair them is real, and it is the decision here.

## Options considered

1. **Reconstruct the records from the orphaned frames.** Rejected on two counts.
   `SubmitMessage.Start` carries `testcaseIds`, `exampleTestcases`, `challengeableId` and
   `challengeableType`, none of which any later frame repeats — so a reconstructed grading is
   already less than a real one. Worse, *binding a stretch of the file to the attempt it
   belongs to is inference*: the file is per-lesson and append-only, several gradings sit in
   it end to end with no separator and no timestamp, and today's match is unique only by
   accident. Attaching an 18/18 PASS to the wrong attempt is exactly what CLAUDE.md forbids —
   substituting a default when identification fails.
2. **Correct the two records by hand.** Tempting: the log is append-only and corrections are a
   supported operation ([[decisions/2026-08-06-record-corrections-by-append]]). Rejected
   because a hand-written correction is indistinguishable in the log from a measured one, and
   the whole value of this repository is that its records were observed.
3. **Delete them, and the wrong records with them.** Rejected outright — discarding originals
   is forbidden, and a wrong record that is *known* to be wrong is more useful than a gap
   nobody can see.
4. **Report them, everywhere the record is read from.** Chosen.

## Decision

`RawSessionLog.orphans()` answers what is stranded — lesson, frame count, path — and two
places say it:

- **Startup**, at every boot, not once on the day. A warning that scrolled past is
  indistinguishable from a complete record afterwards.
- **`stats`**, as `incompleteHistory`, naming the lessons and counting the frames.

The field is **absent when there is nothing stranded**, so its presence is the signal — the
same rule the MCP surface already applies to missing values (`docs/mcp.md`, "Missing data looks
missing"), one level up: what can be missing is not only a field but the record itself.

Deliberately a count and a path, never a parse. Saying "3 gradings" would be a claim the store
cannot support, since it cannot segment the file without the same inference option 1 was
rejected for.

## Rationale

The consumer that matters is an AI asked to diagnose weaknesses from this history. **A
confident diagnosis over a record with silent holes is worse than no diagnosis**, and it is
precisely the failure this project exists to avoid at the capture layer — carried up to the
read layer, where nothing had been done about it.

The counts in `stats` are the basis of every claim about the learner. A denominator that is
quietly wrong produces claims that are quietly wrong, and no reader can tell.

## Accepted costs

- **The two wrong records stay wrong.** `UNKNOWN 0/0` remains next to frames that say
  otherwise. What changes is that the disagreement is now visible from the read side rather
  than only to someone who thinks to look in a directory.
- **`incompleteHistory` says a history is incomplete without saying by how much.** It counts
  frames, not gradings, because the file cannot be segmented honestly. A reader learns that
  something is missing, not what.
- **It appears only on `stats`.** `submissions`, `review_queue` and `slow_passes` read the same
  incomplete history and say nothing. `stats` is where a total is claimed, which is where the
  denominator matters most — but this is a judgement about where a warning is loudest, not a
  guarantee that every path carries it.
- **Nothing prunes the orphan directory.** It grows, slowly, forever. That is the constitution's
  rule about originals, and the cost is a directory that is one day large enough to notice.

## Outcome

Four tests on the query, including one for a stray file in the orphan directory — a `.DS_Store`
counted as "lesson 0" would report a hole that never existed — and two on `stats`, one of which
exists only to keep the field absent when the history is whole.

Not closed by this: whether the two stranded gradings are ever recovered. That would need a way
to bind frames to an attempt that is not a guess, and no such way exists in the protocol.


---

## Amended 2026-08-11 (#187): every answer admits the holes, from one place

`stats` was the wrong scope, and the argument for it — a total is where a denominator matters
most — was true and insufficient. A pass whose frames were orphaned is **a problem
`review_queue` will never schedule** and **a reading `slow_passes` cannot rank**; neither said
anything.

The fix was not to copy the block into five more tools. `McpToolInvoker` already has one wrap
point that every result passes through, so the field moved there: the special case was **removed**
rather than multiplied.

Two smaller decisions came with it:

- **The answer carries counts; the prose moved to the tool descriptions.** A client receives
  descriptions once from `tools/list`, and a full paragraph on every result is weight paid on
  every call. The explanation belongs where explanations are read once.
- **Still absent when the history is whole**, on every tool. Presence remains the signal, and
  a test pins the absence on three tools rather than one — a field that is always there is a
  field nobody notices.
