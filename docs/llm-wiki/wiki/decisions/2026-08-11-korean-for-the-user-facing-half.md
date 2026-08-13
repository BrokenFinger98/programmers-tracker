---
type: decision
project: programmers-tracker
tags: [language, documentation, open-source, guards, drift]
author: BrokenFinger98
created: 2026-08-11
updated: 2026-08-13
sources: [decisions/2026-08-04-english-only-artifacts, decisions/2026-08-10-guards-must-prove-they-ran, concepts/assumption-vs-measurement, raw/sessions/2026-08-11-capture-defects-found-by-solving.md]
---

# Korean for the user-facing half, and a guard for the reason it was refused

Supersedes **in part** [[decisions/2026-08-04-english-only-artifacts]]. That decision stands
for everything a contributor reads; this one carves out the five documents a *user* reads.

## Context

The owner asked for Korean versions of the user-facing documents. The expected users are
Korean job-seekers, and asking one to read a 373-line English setup guide before their first
record is a real cost — paid at the exact moment the tool has not yet proved itself.

The 2026-08-04 decision chose English for everything committed. Its option **B was precisely
this request**, rejected in one line:

> **B. Bilingual docs** — every page twice; guaranteed drift (same failure mode as the
> retired decisions.md duplication)

**That objection is correct**, and it is not theoretical here. In a *single-language*
repository, 2026-08-10 alone produced three live examples: `bootstrap.md` claiming "No MCP
server" months after it shipped, the tool count frozen at three across two documents, and
README and `bootstrap.md` disagreeing about `docker compose --build` in a way that cost the
owner a debugging session. #140 cleaned up the last of them the following morning.

So this cannot be reversed by preferring the newer goal to the older one. Either the drift
objection is answered, or the answer is still no.

## Options considered

1. **Keep English-only.** Honest to the accepted decision and costs nothing to maintain.
   Rejected: it optimises the repository's tidiness against the user's first hour, and the
   user's first hour is the thing this project exists for.
2. **Translate everything, contributor documents included.** Rejected on the original
   rationale, which nothing here weakens: the push gate's stderr, `CONTRIBUTING.md` and
   `development-rules.md` are read by strangers who may not read Korean, and the constitution
   is quoted in issues and PRs.
3. **Translate the user-facing half and rely on discipline to keep the pairs in step.**
   Rejected: this is option B with a promise attached, and the three drifts above happened
   *with* discipline and *without* a second copy.
4. **Translate the user-facing half, and make drift fail the build.** Chosen.

## Decision

**Five documents get a Korean twin**, named `<name>.ko.md` beside the original:
`README.md`, `docs/bootstrap.md`, `docs/mcp.md`, `extension/README.md`,
`template/ps-records/README.md`.

**Everything else stays English-only**, unchanged from 2026-08-04: `CLAUDE.md`,
`docs/development-rules.md`, `docs/programmers-protocol.md`, `docs/superpowers/specs/`, the
wiki, commit messages, code comments, hook and tool output.

**Each twin declares the exact source it was translated from**, on its first line:

```
<!-- translated-from: README.md@4dbad970827788a4e9ca66ec7cabbd24919cb7a0 -->
```

`scripts/guards.sh` recomputes `git rev-parse :README.md` and fails when the two differ.
Editing an English page without touching its twin now **breaks the build**, which is the only
thing that would have caught any of the three drifts.

**Hashes, not history.** A guard comparing commit ancestry would need the log, and CI checks
out at `fetch-depth: 1` — it would pass vacuously on every shallow clone, which is exactly how
the English-only check itself sat broken for weeks
([[decisions/2026-08-10-guards-must-prove-they-ran]]). A blob hash needs no history at all,
and reads the index rather than the working tree so a dirty checkout cannot satisfy it.

**Twins opt in by existing.** The guard iterates the `*.ko.md` files that are tracked, so
translations land one at a time instead of as one 888-line commit nobody can review.

## Rationale

The 2026-08-04 decision was not wrong; it was answering a question with no mechanism
available. What has changed is not the goal but what can be enforced — and this repository has
just spent two days learning what an unenforced rule is worth. The English-only check itself
was vacuous on Linux from the day it was written, and nobody noticed until a guard was made to
prove it had run.

The split follows the original rationale rather than overriding it. "The push gate's stderr is
read by strangers" is an argument about **contributors**, and it survives intact on the
contributor half. It was never an argument about the README.

## Accepted costs

- **Five documents now cost double to change.** That is the point — the cost is moved from
  "silently wrong later" to "visibly more work now" — but it is a real tax on every edit to a
  user-facing page, and the person paying it is whoever is in a hurry.
- **The guard catches forgetting, not lying.** Someone can bump the hash without translating a
  word, exactly as `Wiki-Skip:` can carry any reason at all. Guards here are against omission;
  nothing defends against a deliberate false statement, and pretending otherwise would be the
  same false comfort as a green check that never ran.
- **It cannot judge translation quality, or even that the twin is Korean.** It compares
  hashes. A twin that is stale in meaning while current in hash is invisible to it.
- **A mixed-language repository is harder to skim.** A contributor browsing `docs/` now sees
  each page twice.
- **Five is a boundary someone will want to move.** The next document that feels
  "user-facing enough" starts an argument this ADR only half settles; the list is explicit in
  CLAUDE.md so that moving it is a visible change rather than a habit.

## Outcome

Shipped with `README.ko.md` in the same change, so the mechanism was proved on a real pair
before four more were promised — the guard was watched failing on a deliberately bumped source
and passing after the marker was corrected. The remaining four twins follow.

## Amended 2026-08-13 (#261): a sixth twin, and why it does not reverse this page

The owner asked for `CONTRIBUTING.ko.md` — the exact document Option 2 above named when
rejecting contributor-document translation. The conflict was raised and the owner reaffirmed.

What reconciles it: the rejection's rationale was about what contributors **produce** — commits,
comments, wiki pages reviewed by strangers — and `CONTRIBUTING.md` is the document that *tells a
Korean speaker how to produce those in English*. Translating the instructions strengthens the
rule they teach. The contributor **artifacts** stay English; nothing in the original rationale
is weakened, which is why this is an amendment and not a supersession.

The constitution's list moves 5 → 6, the drift guard covers the new twin like the others, and a
seventh remains a change to the list.

