---
type: source
project: programmers-tracker
tags: [wiki, tooling, provenance, failed-attempts]
created: 2026-08-11
updated: 2026-08-11
sources: [raw/sessions/2026-08-11-backfilling-the-raw-layer.md]
---

# 2026-08-11 back-fill session summary

Provenance for the four back-filled sessions of the same day.

## Key claims

1. Nothing was lost. The pre-compact hook copied this project's transcript to the **central**
   wiki's `raw/inbox/`, and the original is still under `~/.claude/projects/`.
2. The hook hardcodes `$HOME/Desktop/llm-wiki/raw/inbox`. The rule agreed for this project on
   2026-08-04 was repo-local when the repo has `docs/llm-wiki/` — and this repo has no
   `raw/inbox/` at all, so that half was never built.
3. The harness was built to **force** the ingest. It preserved the material and forced nothing,
   because preservation and ingestion are separate steps and only the first was automated.
4. Transcript timestamps are UTC; a first pass sliced days on them and would have filed each
   evening under the following day. KST slicing was required.
5. The back-filled pages are honest but not equivalent to a same-day ingest: the assistant's
   in-turn reasoning is largely absent, and the selection was made six days later by a
   participant.
6. Reconstructing a raw session from the wiki pages citing it stayed refused. The transcript
   changed the situation; the principle did not.
7. Third instance of one shape — visible half of a practice kept, substance dropped: the
   `log.md` line without its raw, the `sources:` citation without its file, the snapshot without
   its ingest.

## Pages this source updated

[[concepts/tests-that-explain-defects]] · [[decisions/2026-08-10-guards-must-prove-they-ran]]
