# 2026-08-28 — the first real record, and the three things it showed

Raw session record. Immutable (wiki schema §1). Recovered from the inbox snapshot of session
`b240e44e` on 2026-09-30; 08:48–09:47 KST. Three PRs (#326, #328, #330); only #326 carried a wiki
change, so the session had no raw record until now.

---

## The record itself was fine

The owner solved **lesson 181945**, the first stdin-style problem ever recorded. PASS 3/3,
attempt 1, three runs — the first two compile errors, kept rather than dropped, which is the part
Programmers' own history does not keep. `Solution.java` equalled `attempts/001.java`;
`examples.json` carried the raw newline inside a quoted string exactly as protocol §7.1 describes.
The runner produced `ALL PASS` when I ran it.

Two things stood out on the page, and a third came from running the runner.

## 188 hours "Elapsed" for a six-minute problem (→ #327 / PR #328)

```
elapsedSec = 677131   wall clock since the page was first opened
focusedSec =    365   the tab actually visible
```

Not a data defect: `elapsedSec` is what #205 designed, calendar time from first encounter, and its
own KDoc says it *"is simply not the one the name suggests"*. The MCP surface had warned about the
pairing since #287. **The file a person opens said nothing** — #287 in reverse, and worse, because
nobody doubts a column called Elapsed. So the word went: `Since opened | Focused`, side by side,
both in the frontmatter that Obsidian's tables and the MCP read. Absent, never zero: the sensor is
optional and `0m00s` would claim the problem took no time — the argument
[[decisions/2026-08-10-sensor-observations]] had already made about this field.

## Raw HTML in the statement (→ #325 / PR #326)

```
<div class="highlight"><pre class="codehilite"><code>abcde
</code></pre></div>
```

`pre` had a handler since the parser was written; `div` did not, so the wrapper fell to the
unknown-element fallback — which preserves rather than drops — and took the `pre` inside it
along. Two shapes of statement exist: a solution-function problem states its examples as a
`<table>`, a main-reads-stdin problem as code blocks, and every problem in the vault had been the
first kind. `shouldNotContain "<div"` had been in the test since it was written and passed for
want of a fixture that could fail it.

**The old fixture's header recorded "no nested `<div>` anywhere inside" as measured fact.** It was
a fact about the five problems it was taken from, written as one about the region. Corrected in
place; a new fixture measured from the live page, shape verbatim, prose invented (dev rules §7.3).
Three tests, all verified failing with the fix removed. The wiki gate blocked the push, and instead
of a skip the case went into [[concepts/assumption-vs-measurement]] — it is exactly that page.

## The runner's own instruction did not work before JDK 22 (→ #329 / PR #330)

`java RunnerTest.java` needs JEP 458 (JDK 22+); before it the launcher compiles only the named
file and `Solution` beside it is invisible:

```
RunnerTest.java:19: error: cannot find symbol
            Solution.main(new String[0]);
```

First read as my environment's fault — my shell's `java` was 21 — and it was, which is the point:
the tool requires 25 and CI runs 25, so nothing was going to catch it, while the record repository
is the owner's own folder opened with whatever they have, and Programmers offers Java 8 and 11.
`javac RunnerTest.java Solution.java && java RunnerTest` works on every JDK since 8. Verified on
the JDK 21 that had failed, not only in a test. Java only; the other six compile explicitly or are
interpreted.

## Two mechanics worth keeping

- **Parallel PRs and `progress.md`.** #328's merge left #329's branch with zero checks — the
  classic symptom of a `progress.md` conflict when every branch appends there. Fixed by merging
  main; the note lives in memory now, not only here.
- **"Did the rebuild take?" is answered by the bytes, not the timestamp.** The jar was pulled out
  of the running container and its classes checked for the changed strings. macOS `strings`
  refused the class files: the Java magic `0xCAFEBABE` is also the Mach-O fat-binary magic, so
  the tool tries to parse them as Mach-O. Python byte matching instead.
