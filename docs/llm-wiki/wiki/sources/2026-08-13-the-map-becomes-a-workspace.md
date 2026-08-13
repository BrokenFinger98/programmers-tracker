---
type: source
project: programmers-tracker
tags: [vault, obsidian, measurement, ci, failed-attempts]
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-13-the-map-becomes-a-workspace.md]
---

# 2026-08-13 session summary — the owner starts using the vault, and the vault changes shape

## Key claims

1. **#231 was reversed within a day, and correctly**: node size in Obsidian is link count, so 510
   catalog edges made `implementation` (29 links, 2 the owner's) the biggest node on a map of
   someone with four solved problems. The dust cloud had been the *accurate* picture —
   [[decisions/2026-08-13-node-size-is-what-you-solved]].
2. **"A run is not an attempt" needed telling at four separate sites in one day** (#235, #237,
   #241's family, #248). The rule now lives in `SubmissionRecord.isSubmission()`. See
   [[concepts/assumption-vs-measurement]]'s invariant-by-convention section.
3. **The container's clock was UTC and every rendered time was nine hours off** (#243). Fixed
   with a neutral default plus a startup announcement; the zone parameter ended with **no
   default**, because `systemDefault()` makes an omitting caller behave differently per machine —
   which CI proved by turning `DailyBackupTest` red on four runners while it stayed green locally.
4. **CI wall clock 6m02s → 5m08s, measured on the runners** (#246): `maxParallelForks` and
   `--rerun` — the Gradle cache was never the gap.
5. **The vault stopped committing the editor** (#234, option B) and `progress.md` joined
   `merge=union` after five identical keep-both conflicts (#252).
6. **The rejected `_dashboard.md` arrived as a Base** (#255): a query, not derived data — seeded
   when absent, never touched again, verified in both directions. The template-vs-server choice
   was the owner's and was right: a template reaches only repositories that do not exist yet.
7. **The record now says `algorithm | database`** (#256), from `ChannelKey.kind` — a fact the
   server held before the first frame and never wrote down. The wire value is `database`, not
   `sql`; guessing the vocabulary would have shipped a word the protocol never uses.
8. **Four "known and unfiled" notes in goal.md described finished work.** Session expiry,
   subscription health, `hintLevel`, the CI 429 — all resolved long ago. Memos outlive work;
   grep does not.
