# 2026-08-11 — back-filling the raw layer, and why the safety net missed

Raw session record. Immutable (wiki schema §1).

The provenance page for the four back-filled sessions written the same day:
`2026-08-05-capture-pipeline-built-end-to-end`, `2026-08-06-catalog-runners-and-the-record-repository`,
`2026-08-07-adversarial-review`, `2026-08-10-sensor-verified`.

---

## How it started

`raw/sessions/` had been empty since 2026-08-05 while `log.md` recorded 33 ingests, and two
ADRs cited raw sessions that were never written (#161). The owner then noticed that *something*
had been accumulating elsewhere and asked what it was.

The central wiki at `~/Desktop/llm-wiki/` holds ingested sessions from several other projects
of the owner's — none from this one. What it does hold, in `raw/inbox/`, is **transcript dumps**:

```
precompact-programmers-tracker-b240e44e.jsonl    33 MB
2026-08-04-155609-programmers-tracker.jsonl     2.3 MB
.precompact.log
```

and the log inside it names the mechanism outright:

```
2026-08-06 00:56:17 precompact trigger=auto proj=programmers-tracker sid=b240e44e
2026-08-07 11:43:17 precompact trigger=auto proj=programmers-tracker sid=b240e44e
2026-08-10 16:38:26 precompact trigger=auto proj=programmers-tracker sid=b240e44e
2026-08-11 15:34:21 precompact trigger=auto proj=programmers-tracker sid=b240e44e
```

**Nothing was lost.** The dump is a copy of `~/.claude/projects/<project>/<session>.jsonl`,
which is still there and is a superset (14,872 lines against the dump's 14,456). The second
file is a different session, `3e0e704d`, and it is also still on disk.

---

## The root cause, and it is older than the symptom

`~/.claude/hooks/wiki-archive-precompact.sh` writes to one hardcoded location:

```bash
inbox="$HOME/Desktop/llm-wiki/raw/inbox"
```

The rule agreed for this project on 2026-08-04, in session `3e0e704d`, was different:

> 레포에 docs/llm-wiki/ 가 있으면 그쪽 inbox 로, 없으면 전역으로.

This repository has `docs/llm-wiki/`. It has **no `raw/inbox/`** — the repo-local half was never
built. So four compactions' worth of this project's conversation went to the one place this
project never looks, and the wiki that governs this repository stayed empty while a safety net
one directory over was doing its job perfectly.

The owner's stated reason for building that harness, the same day, is the sharpest thing in the
record:

> 원래 항상 발생하던 문제가 세션이 길어져서 auto-compact가 진행되면 대화 내용이 날라가고
> 만약 wiki-ingest를 안했을때 그대로 손실되는 문제가 있거든 그걸 강제하고 싶은거야.

The harness was built to **force** the ingest. It preserved the material and forced nothing —
because preservation and ingestion are separate steps, and only the first one was automated.
The hook is a snapshot, not a gate.

Two smaller notes on the hook, both by design and both worth knowing: it keys the filename by
session id, so a marathon session's later compaction overwrites its earlier one (one file per
session, not per compaction); and it always exits 0, so it can never block a compact. Neither
caused loss here. Both mean the inbox is a convenience copy, not an archive.

The hook lives in `~/.claude/` and is outside this repository, so nothing in this change fixes
it. It is stated here so the next person does not rediscover it.

---

## What these four pages are, and what they are not

Written from the session transcript, the git history, and the PR record. Concretely:

- **user messages per day**, sliced on KST rather than the transcript's UTC — the first pass got
  the day boundaries wrong by nine hours and would have filed the evening of one day under the
  next
- **the compaction summaries embedded in the transcript**, which are dense and contemporaneous
- **the four critics' messages of 2026-08-07**, quoted from the transcript rather than
  reconstructed
- `git log` per day, for what actually landed

They are honest, and they are not equivalent to a same-day ingest:

- **The assistant's own reasoning at the time is largely absent.** What survives is what was
  said out loud and what was committed. Dead ends that were abandoned inside a single turn
  mostly did not survive
- **They were written by the participant, six days later.** The transcript is primary, but the
  selection is not. A same-day ingest chooses differently because it remembers what felt
  uncertain
- **2026-08-09 is genuinely empty**, and 2026-08-08 is one question. Not a gap in the record — a
  gap in the work

What was refused: reconstructing a raw session from the wiki pages that cite it. That was the
option available on 2026-08-11 morning for the two dangling citations, and it was declined then
for the reason it is still declined — a source derived from the page citing it is not a source.
The transcript changed the situation; the principle did not.

---

## The pattern, third instance

[[concepts/tests-that-explain-defects]] is about the visible half of a practice surviving while
the substance stops. This is the same shape at the level of the harness:

| Visible half, kept | Substance, dropped |
|---|---|
| `log.md` ingest line | the raw file beside it |
| `sources:` citation in an ADR | a file at that path |
| the pre-compact snapshot | the ingest that reads it |

All three look like the practice from outside. All three are checkable mechanically, and two of
them now are (`scripts/guards.sh` §6, and the raw files this change adds). The third — a
`log.md` line with no raw of that date — is still not, and is stated as remaining risk rather
than fixed.
