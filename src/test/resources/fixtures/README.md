# Protocol fixtures — measured message captures

Each `.jsonl` file is a sequence of raw ActionCable wire frames (one JSON object per
line) transcribed from the measured captures recorded in
[`docs/programmers-protocol.md`](../../../../docs/programmers-protocol.md)
sections 4–8 and the section 15 verification log. Korean `msg` strings are kept
verbatim — failure classification depends on them (protocol doc section 7).

| Fixture | Source | Notes |
|---|---|---|
| `algorithm-pass.jsonl` | §5, §15 #6 — lesson 120804, 16/16, `finish` present | testcase frames are a representative subset (2 of 16) |
| `algorithm-wrong.jsonl` | §5, §15 #1 — lesson 120803, partial score `1.4` | wrong-answer testcase frame is the §5 measured example |
| `algorithm-timeout.jsonl` | §7, §15 #11 — lesson 120805, `실패 (시간 초과)`, `run_time`/`memory_size` null | |
| `algorithm-runtime.jsonl` | §7, §15 #10 — lesson 120810, `실패 (런타임 에러)` | |
| `algorithm-compile.jsonl` | §7, §15 #9 — lesson 120820, same `msg` as runtime error (indistinguishable on submit path) | |
| `sql-pass.jsonl` | §6, §15 #7 — lesson 131528, snake_case fields, **no `finish` frame** | |
| `sql-run.jsonl` | §6, §15 #8 — `returned_rows` double-encoded, `msg` explicitly null | |
| `algorithm-run-error.jsonl` | §7 run path, §15 #12–13 — HTML-escaped compiler output / stack trace | |
| `algorithm-run-pass.jsonl` | **Our own live capture**, lesson 120804, 2026-08-04 and reproduced 2026-08-05 (issues #6, #10) | the only fixture not transcribed from the protocol doc — see below |

### `algorithm-run-pass.jsonl` — provenance

Every other fixture is transcribed from the protocol document. This one was captured by
this project's own client during the issue #6 live verification and reproduced byte-alike
after the Spring Boot 4 upgrade (#10), because the protocol document records the run
success *sequence* (§10) without a frame-level transcript of the terminal `result`.

It is the measured evidence for three things the fixtures otherwise could not test:

- the algorithm **run** path terminates at `result`, not `finish`
- run testcases identify themselves by **`index`** (0-based), not `testcaseId`
- testcases arrive **out of order** — `index 1` precedes `index 0` in this capture

## Scrubbing (development-rules §7.3)

- Ratings substituted: `oldUserRating`/`newUserRating` → `1000`/`1001`
- `surveyUrl` → `/custom_form_groups/0`, `finishModalLink` lesson ids → `0`
- `challengeable_id`/`testcaseId` values not documented for a lesson are substituted
  with plausible placeholders (documented ids — 14643, 2778, 154893/154894, 5437/5438 — kept)
- No emails, user ids, or session cookies appear in any fixture
