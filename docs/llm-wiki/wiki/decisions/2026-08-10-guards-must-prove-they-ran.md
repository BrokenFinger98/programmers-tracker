---
type: decision
project: programmers-tracker
tags: [ci, guards, tooling, locale, english-only]
author: BrokenFinger98
created: 2026-08-10
updated: 2026-08-11
sources: [decisions/2026-08-04-english-only-artifacts, decisions/2026-08-05-ci-guard-scoping, concepts/assumption-vs-measurement, raw/sessions/2026-08-11-capture-defects-found-by-solving.md, raw/sessions/2026-08-10-sensor-verified.md, raw/sessions/2026-08-11-backfilling-the-raw-layer.md]
---

# A guard that cannot run must fail, not pass

## Context

The English-only guard ([[decisions/2026-08-04-english-only-artifacts]], enforced by
`scripts/guards.sh` §3) reported `ok  no Korean prose in Kotlin comments` on a tree that
contained Korean prose in a Kotlin comment. Not once — on the branch (`f4f77b3e`) and again
on `main` after the squash (`c5d2db51`), both green.

The offending lines were in `SensorObservation.kt`, shipped by #121. They cleared the local
gate too, for a second and unrelated reason.

## What was actually wrong

Measured in an `ubuntu:24.04` container against this tree. The check was

```bash
korean_comments=$(git grep -nIE '^[[:space:]]*(//|\*|/\*).*[가-힣]' -- '*.kt' '*.kts' || true)
```

`[가-힣]` is a multi-byte **character range**, which `git grep` resolves against the locale.
It agreed with nobody:

| Locale | Result |
|---|---|
| `C` (LANG unset) | exit 0, **1006 matches** — bytes read as a range, every `§`/`—`/`→` comment fires |
| `C.UTF-8` (GitHub Actions runner) | **`fatal: Invalid collation character`, exit 128, no output** |
| macOS `en_US.UTF-8` | exit 0, 2 matches — the intended behaviour |

CI is the middle row. And `|| true` cannot tell exit 1 (nothing found) from exit 128 (the
search never happened), so the crash was indistinguishable from a clean tree. **The guard
was vacuous on Linux from the day it was written and only ever worked on the author's Mac.**

The local miss was separate: `git grep` searches the index, the gates were run before
`git add`, and a file that is not yet tracked is a file the guard has never read.

## Options considered

1. **Set a UTF-8 locale in CI.** Smallest diff. Rejected: it pins correctness to an
   environment variable in a workflow file, one `runs-on` change away from silently
   reverting, and does nothing for a contributor's machine.
2. **`git grep -P '\p{Hangul}'`.** Reads best. Rejected on measurement — PCRE Unicode
   properties need git's UTF mode, which git enables from the locale, so it returned
   **zero matches** in the container. It fails the same way for the same reason.
3. **Match the UTF-8 bytes under a pinned `LC_ALL=C`.** Chosen.

## Decision

Three changes, and the third is the one that generalises.

**Match bytes, not characters.** Hangul syllables U+AC00–U+D7A3 are three UTF-8 bytes led
by `\xea`–`\xed`. `LC_ALL=C` is pinned so bytes stay bytes everywhere. Verified: exactly
2 matches on macOS and on Ubuntu under `C`, `C.UTF-8` and `en_US.UTF-8` alike. The
punctuation that legitimately appears in this repository's English comments — `§` (`\xc2`),
`—`/`→`/`⚠` (`\xe2`) — is outside the range, so the narrowness the original check was
designed for survives.

**Never collapse an error into a negative result.** `|| true` is gone. `git grep` exits 0
with matches, 1 with none, higher on error; exit > 1 now reports the exit code as a guard
failure.

**Make the guard prove it works before its silence is believed.** Two canaries run first:
one comment that must match (a synthesised U+AC00), one English comment using `§ — →` that
must not. Either firing reports that the *check* is broken. This is the part that outlives
the specific bug — every earlier fix in options 1 and 2 would also have "passed" while
doing nothing, and nothing in the script could have told the difference.

Scope also widened slightly: `--untracked` closes the pre-`git add` hole, and `*.js` joins
the pathspec because `extension/sensor.js` carried the same Korean prose with no guard over
it at all. Shell scripts stay excluded on purpose — `guards.sh` quotes the measured protocol
literal `실패 (시간 초과)` to explain why literals are legitimate, and that quotation is not
prose we wrote.

## Accepted costs

- The pattern is now unreadable as text. A byte range says nothing about Korean to someone
  reading it; the comment above it has to carry the meaning, and a comment can rot. The
  canaries are the mitigation — they fail loudly if the bytes stop meaning Hangul.
- The byte range covers more than Hangul (U+A000–U+D7FF, so Yi syllables and part of the
  CJK area). For a check whose purpose is "no non-English prose", catching neighbouring
  scripts is not a cost worth engineering away.
- `--untracked` means a scratch `.kt` left in the working tree fails the gate. Correct for
  a pre-push gate, mildly annoying mid-experiment.

## Outcome

The guard fails on the tree that shipped green and passes after the two comment sites are
translated, identically on macOS and Ubuntu. All four failure modes were exercised rather
than reasoned about: cannot-detect, over-matches, search-crashed, clean.

The broader lesson belongs with [[concepts/assumption-vs-measurement]]: a green check is
evidence only if something proves the check ran. This one had been reporting on a search it
never performed, and no amount of reading the script would have shown that — only running it
somewhere other than the machine that wrote it.

---

## Applied again 2026-08-11 (#165, #169, #171): three more layers of the same failure

The canary pattern turned out to be the reusable half. Three guards now open with a proof of
their own machinery before their silence is trusted, and each closes a place where the visible
half of a practice survived while the substance stopped:

| § | Catches | The failure it is drawn from |
|---|---|---|
| 5 | a Korean twin whose English source moved past its `translated-from` hash | the objection that refused the twins once ("guaranteed drift") |
| 6 | a `sources:` entry that resolves to nothing | two ADRs citing raw sessions that were never written |
| 7 | a `log.md` ingest line with no raw session of that date | 33 logged ingests over an empty `raw/sessions/` |

§7 is the one the whole week was about, and two things in it are decisions rather than
mechanics.

**No date floor.** The obvious design is to grandfather history — every existing line would
fail an added rule, so exempt them and start counting from today. That was checked instead of
assumed, and it was wrong: after the #163 back-fill **every ingest date already had a raw
session**, so the rule applies to the whole file with no exemption. Worth stating because an
exemption list is what a rule decays into, and the cheapest moment to avoid one is before it is
written.

**A date is matched from a raw session's filename *or its H1*.** A day's work does not always
get its own file — 2026-08-08 is one question, recorded inside a page named for 2026-08-10
whose heading reads `# 2026-08-08 / 2026-08-10 — …`. Filename-or-heading is as much prose as a
guard should be willing to read: structured enough to be mechanical, loose enough to describe
sessions the way they actually happened. Negative-tested by removing the date from that heading,
which is what makes the dependency real rather than incidental.
