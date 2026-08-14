# 2026-08-14 — the warnings, and what was under them

Raw session record. Immutable (wiki schema §1). Continues
`2026-08-14-the-night-the-records-learned-the-question.md` — the same session, from the point
where the owner wiped the record repository to test as a first-time user and rebuilt the
container themselves.

The build printed two warnings. Both turned out to be about something other than the warning.

---

## The owner's own build output was the finding

The rebuild was the owner's command, not mine, and its output carried:

```
w: .../adapter/store/ProblemIndex.kt:79:33 Unnecessary safe call on a non-null receiver of type 'String'.
```

Written by me the previous day, in #295. It had printed on every build since and nobody had read
it — including me, who had run the same build a dozen times.

**This is the pattern of the whole session one more time**: the finding came from an outside
reference (the owner's terminal), not from re-reading the code that produced it.

## The safe call was standing on something live and untested

`?.` on a non-null `String` is a no-op. What made it worth a PR was the expression underneath:

```kotlin
val title = record.title.ifBlank { null } ?: record.lessonId.toString()
```

`SettledCapture.toRecord` writes `title = problem?.title.orEmpty()`. So **a problem the cached
catalog has never seen records an empty title** — blank is how "unknown" is spelled in the JSONL,
and `RecordLayout` names that directory `<lessonId>` with no slug. The index row links by the id
rather than rendering `[](...)`.

Every case in `ProblemIndexTest` passed a real title. The fallback had never been executed by a
test. Pinned in #309/#311.

## Removing one warning made the other one visible

With #309 merged the build printed exactly one line, and it had been buried under the noise:

```
w: .../adapter/cable/CableChannelSubscriber.kt:126:18  Flow.timeout is @FlowPreview
```

That is the **silence deadline on the observation socket** — the only thing that notices a channel
has gone quiet, and the line #94 moved above the heartbeat filter.

I deliberately did not annotate it inside the typo fix. `@OptIn` suppresses nothing; it is a
written acceptance that a coroutines upgrade may break that line, which is a decision with an ADR
attached. Filed as #310 and resolved separately →
[[decisions/2026-08-14-a-preview-api-under-a-test]].

**The deciding fact was found by looking for a test, not by reasoning about risk.**
`heartbeats hold the socket open and never reach the capture` runs a 200 ms deadline against a
flow emitting for 600 ms and asserts zero reconnects. A silent change of meaning — *gap between
emissions* → *total collection time* — turns that test red. Removal of the API is a compile
failure. Both failure modes are caught, so the acceptance is affordable; without that test it
would not have been.

## #308 — an improvement that could not reach an existing install

#307 narrowed `.obsidian/` to `.obsidian/workspace.json`. `RecordRepositoryIgnores` adds a missing
rule and edits nothing, so the narrowing reached only repositories created after it — and the
ancestor-aware `alreadyIgnores` from the *same* change made the server correctly decline to add
the narrower rule underneath the broader one already there. The fix for one problem guaranteed
the other.

Same shape as #300 (an improvement that cannot reach an existing install), **different answer**.
The seeds got a ledger because seeds change often: three improvements in two days. Ignore rules
do not — five in the tool's history, one ever narrowed. A sentence in `docs/bootstrap.md`, and the
rule's own comment already carried the instruction before anybody needed it.

The owner's repository turned out to be already correct: the wipe had rewritten that `.gitignore`
from scratch, so the by-hand deletion the issue asked for had happened by accident.

## The correction the owner made

I closed the report by listing three remaining risks and handing the last one back — *"이건 형님이
문제 하나 푸셔야 닫힙니다"*. The answer was **"남은 위험 해결해야하는거 아니야?? 문제 하나
풀라고???"**

Both halves land. The risks were mine to close, and the browser-driven first-run test was
something the owner had already told me to do myself, earlier in the same session: *"너가 직접
브라우저 조작해서 문제 풀면되잖아."* Listing a risk is not reporting; it is deferring.
