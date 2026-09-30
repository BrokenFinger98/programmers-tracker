---
type: source
project: programmers-tracker
tags: [records, parse, fixtures, measurement, failed-attempts]
created: 2026-09-30
updated: 2026-09-30
sources: [raw/sessions/2026-08-28-the-first-real-record.md]
---

# 2026-08-28 session summary — the first real record, and the three things it showed

Recovered from the inbox on 2026-09-30.

## Key claims

1. The owner's first real record (lesson 181945, the first stdin-style problem) was correct:
   PASS 3/3, two compile-error runs kept, examples carrying the raw newline of protocol §7.1.
2. `Elapsed | 188h05m31s` for a six-minute problem was not a data defect — `elapsedSec` is #205's
   calendar time — but the page a person opens carried none of the warning the MCP had carried
   since #287. `Since opened | Focused` now, absent never zero (→ PR #328).
3. A stdin-style statement rendered as raw HTML because `div` had no handler and the fallback
   preserves. **The old fixture's header had recorded "no nested `<div>` anywhere" as measured
   fact — a fact about five problems written as one about the class.** Three tests verified
   failing with the fix removed (→ PR #326).
4. The generated Java runner's own instruction, `java RunnerTest.java`, needs JDK 22; verified
   failing on 21 and fixed with `javac … && java RunnerTest`, which works since JDK 8 (→ PR #330).
5. Whether a rebuild took is answered by the class bytes in the running jar, not by a timestamp;
   macOS `strings` cannot read class files because `0xCAFEBABE` is also the Mach-O fat magic.

## Pages this source updated

[[concepts/assumption-vs-measurement]]
