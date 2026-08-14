---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [obsidian, vault, seeds, record-keeping]
created: 2026-08-14
updated: 2026-08-14
sources: [raw/sessions/2026-08-14-the-warnings-and-what-was-under-them.md]
---

# `dashboard.base` ships in the form Obsidian rewrites it to, and a file that already matches is ours

Two halves. The seed is now byte-identical to Obsidian's own normalisation of it, so opening the
dashboard changes nothing; and `VaultDashboard` adopts any seed whose bytes already equal what it
would write, whatever the ledger remembers.

## Context

`SeedLedger` (#300) decides *ours to update* against *theirs forever* by comparing SHA-256 of the
exact bytes, and the rule it protects is right: **ambiguous means edited, because overwriting
somebody's file cannot be undone from here.**

The premise underneath it turned out to be false for one seed. Measured 2026-08-14, on the owner's
own vault:

| | before | after |
|---|---|---|
| sha256 | `9fc964011f10` | `a3967cdb98c6` |
| comment lines | 15 | 0 |

**Obsidian rewrites `dashboard.base` the moment the Base view renders**, dropping every comment —
ordinary YAML round-tripping, not a bug on their side. The diff is deletions only, with zero
additions, and the same output hash appeared twice from the same input, so the transform is
deterministic.

The consequence: `isUnchanged` answers false, and the server never updates that vault's dashboard
again. **The reader edited nothing.** The two plain-markdown seeds were untouched, which is the
tell — this hits the one seed that is not markdown.

**The first diagnosis of the trigger was wrong and the reproduction is what caught it.** The issue
said "just by having the vault open"; restoring the commented file and waiting 75 minutes with
Obsidian running changed nothing. It needed the view to be *opened*, which is a thing this side
cannot do — the owner did it, and the hash moved within the minute.

## Options considered

**A. Ship the seed in Obsidian's normalised form**, and move the comments somewhere that survives
being read.

**B. Compare semantically** — parse the YAML and compare structures instead of bytes.

**C. Accept and document it.**

## Decision

**A**, plus a rule that lets it reach the vaults already affected.

## Rationale

**The comments were not reaching their reader anyway.** They were destroyed by the first person to
open the dashboard — which is everyone who uses it. Explaining the tables in the vault `README.md`
puts the same content somewhere a reader can actually find twice.

**Obsidian's output is a fixed point of its own transform.** The measured diff removes comments and
blank lines and adds nothing; its output has neither left. So shipping that form makes the
round-trip a no-op, and the hash stays put. This is measured rather than assumed: `a3967cdb98c6`
came out of the same input on two separate occasions.

**B makes "unchanged" fuzzier than bytes**, which is the ledger's entire argument. A semantic
comparison has to decide whether a reordered view or a renamed column is "the same", and every
answer to that is a judgement the ledger exists to avoid making.

**The adoption rule is what makes A worth building.** Without it, A only helps vaults created
after today: every existing vault whose owner ever opened the dashboard **already holds today's
shipped bytes** — Obsidian put them there — under a ledger entry naming the old commented form,
so every later improvement would be declined as an edit nobody made. `VaultDashboard.adopted`
records the hash when the file on disk already equals the shipped text, and that is the one claim
of ownership that cannot cost anybody an edit: there is none, and writing would be a no-op.

This is the same shape as [[decisions/2026-08-13-the-server-prepares-the-repository]]'s problem —
an improvement that cannot reach an existing install — and unlike #308's ignore rules there **is**
a safe automatic answer here, so it gets one.

## Accepted costs

- **The seed is now unreadable as a standalone file.** Seven views of YAML with no explanation in
  it, and the explanation lives one file away. A guard test fails if anyone re-adds a comment, and
  its KDoc says why, because the re-adding would look like an improvement.
- **The vault README is longer**, and its Korean twin with it. Both are seeds, so both had to move
  and the `translated-from` hash bumped.
- **The fixed point is Obsidian's, and it can change.** A future Obsidian that reformats the YAML
  differently reopens exactly this, and nothing here would notice — the guard checks for comments,
  not for agreement with a version of Obsidian we cannot run in a test.
- **`.base` is the only format this reasoning covers.** Any future non-markdown seed needs the same
  question asked about whatever reads it.

## Outcome

Implemented in #314. The owner's vault was the measurement instrument twice: once for the defect,
once for the trigger the first diagnosis got wrong.
