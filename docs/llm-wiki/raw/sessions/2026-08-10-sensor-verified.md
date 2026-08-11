# 2026-08-08 / 2026-08-10 — the sensor proven, and the state moved where the design said

Raw session record. Immutable (wiki schema §1).

Back-filled 2026-08-11 from the session transcript (session `b240e44e`), the git history and
the PR record. This is the page that [[decisions/2026-08-10-sensor-observations]] cited before
it existed — see `raw/sessions/2026-08-11-backfilling-the-raw-layer.md`.

2026-08-09 has no entries at all. 2026-08-08 has one message, and it opens this page because it
is what started the run-raw-sessions decision.

---

## 2026-08-08, one question

> .ps/raw 가 왜 쌓이는데 그거부터 알려줘. 뭐로부터 쌓이는건데?

Four files on the machine, every one `"action":"run"`. The cause was a decision that had never
been finished: `movesRaw` is false for a run **on purpose** (design §5.1 — a run makes no
`attempts/NNN` file, because Run is pressed dozens of times while writing code), but "we do not
file it under attempts" was implemented without answering *"then where does the original go?"*

Two consequences, and only the second is interesting:

1. Files accumulate. One is ~1.1 KB; thirty runs a day is ~12 MB a year. Disk is not the cost
2. **`RawSessionReconciler` re-reads and re-settles every run ever captured, on every boot.**
   The dedup key discards them, so nothing is wrong in the output — the work list simply grows
   forever, and a growing boot cost with a correct result is exactly the kind of defect that
   never announces itself
3. A run record's `rawPath` was the bare session id, resolved against the record root, pointing
   at nothing. development-rules §2.4 — "the record points at that file" — was false for the run
   half of every capture

This was escalated rather than decided, because design §5.1 and the constitution's "never
discard originals" pull opposite ways and satisfying both makes `rawPath` nullable, which
changes a schema the design defines. Owner's call → [[decisions/2026-08-08-run-raw-sessions]],
shipped 2026-08-10 as #112.

---

## 2026-08-10 — the day the owner lost the thread

> wiki-query나 state의 문서들을 토대로 처음부터 지금까지 작업의 흐름을 파악하고 얘기해봐.
> 너가 지금 하는 말을 하나도 못알아듣겠네. 내가 어느순간부터 너한테 알아서 작업하라 해서
> 그런가 지금 어떤 작업을했고 어떤 상태고 이런게 파악이안되. 우리 서비스의 목적이 뭐였는지
> 좀 다시 명확하게 해보자.

This is a finding about the assistant, and it belongs in the record at least as much as the
code does. Five days of autonomous work had been reported as issue numbers and defect names,
never re-grounded in what the product is for. The autonomy was granted; the reporting
convention that should have come with it was not, and the owner had to ask for it back.

What came out of the re-grounding was a **scope cut**, not a summary:

> 이미 예전에 풀었던 문제에 대해서는 신경쓰지마 앞으로 새로 풀 문제에 대해서만 신경써.

That deletes design §6.3 — reactivation diagnosis over historical problems, which the design
itself had marked P0, and whose `reset` action was destructive and unverified. The design's own
priority was wrong about what the owner wanted, and one sentence settled it.

---

## Why a browser extension at all

The owner's question was the right one:

> 처음에 .ps/session에 session_id 넣으면 그 session_id는 내 고유 id여서 다른문제를 들어가도
> 고정아니야?

Yes — the cookie is fixed. What is not fixed is **which problem, in which language**, and the
server cannot know it: `POST /watch` needs a lesson id and a language, and nothing on the
server's side observes a browser tab. Two more fields are things the server can *never* know —
how long the tab was actually focused, and whether the questions tab was opened while stuck.

The instruction that shaped the rest was **"응 실측부터 해봐"** — measure first. It paid
immediately: a candidate field, *"did you look at other people's solutions"*, was measured to
return **401 until the problem is solved**, so it could only ever have recorded `false`. It
would have sat next to `hintLevel`, which was already a measurement nobody took and was removed
the same day (#137). Two dead fields avoided by trying one URL.

Four fields ship; nothing else. No code, no verdicts, no cookies, and no request to Programmers.

---

## Proven, then proven again

The extension was loaded unpacked in Chrome and watched to work — badge green,
`watching lesson 181947 in java (refreshed)`, matched against the server log. That closed the
last gap between this tool and someone other than its author using it.

The first attempt failed, and how it failed is the memorable part. The badge read:

```
400 INVALID_REQUEST — challengeableId is missing
```

a field the current server does not even read. **The container was four days old.** The
assistant had handed over `docker compose up -d`, and the owner's correction was exact:

> 옵션 안넣었어. 방금 너가 준 명령어에 빠져있었잖아.

The rebuild note existed — in a blockquote *below* the copyable command block, where nobody
copies from. `docs/bootstrap.md` now carries `--build` inside the command itself. A command
handed to a user must be complete; a caveat beside it does not get copied.

---

## The guard that had never run

`scripts/guards.sh` failed locally on two Korean comment lines in `SensorObservation.kt` and
**CI reported `ok`** on the same branch with the same file committed. The guard, not the code,
was the defect (#123/#124):

`[가-힣]` is a locale-collated range. On the CI runner's `C.UTF-8` it died with
`Invalid collation character` (exit 128); in a bare `C` locale it matched **1006 English
comments**; only the author's macOS `en_US.UTF-8` gave the two real hits. And `|| true`
swallowed the crash, so a search that never ran was indistinguishable from a clean tree.

Rewritten to match **bytes** with `LC_ALL=C` pinned, with canaries that prove the machinery
before its silence is trusted. It is also the ADR that later justified §5 (Korean twins) and §6
(wiki citations): [[decisions/2026-08-10-guards-must-prove-they-ran]].

---

## The rest of the day

- **State moved into the record repository** (#126/#127) — the M3 finding from 2026-08-07. Every
  `under(recordRoot)` helper had a KDoc saying design §5.1 and every caller was in `src/test`.
  Moving it required the server to add `.ps/` to the user's `.gitignore` at startup, or
  `git add --all` would commit the capture history alongside the records it describes.
  `session` and `watch-token` deliberately stay in the project checkout — credentials must not
  travel with a repository that gets pushed
- **A reconciled duplicate that never left the work list** (#131) — re-read on every boot since
  2026-08-06
- **`review_queue`** (#133) settled a standing conflict between the two founding documents:
  design §6.4 asks the server to compute a confidence, CLAUDE.md forbids rule-based analyzers.
  The boundary drawn was **diagnosis versus scheduling**, and its price is that every item ships
  the facts that scheduled it ([[decisions/2026-08-10-scheduling-is-not-diagnosis]])
- **`slow_passes`** (#135) applied the same boundary a second time, and deliberately invented no
  baseline: a two-pass record set has no peers to compare against, so the tool reports
  `untimed` rather than a number
- No migration code was written for the state move. No releases, forks or stars exist —
  confirmed rather than assumed — so manual migration of one machine plus documentation is the
  YAGNI-correct answer
