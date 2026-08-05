# 2026-08-05 — Phase 1 implementation, full-stack review, adversarial design review, Boot 4 upgrade

Immutable session record. Covers issues #6 (ActionCable client), #8 (design revision),
#10 (Spring Boot 4.1 + JVM 25), and the discussions between them.

---

## 1. Issue #6 — Kotlin ActionCable client, built through Orca orchestration

Three tasks dispatched to supervised workers in the current worktree: Gradle scaffolding →
protocol DTOs/parsing (TDD, measured fixtures) → ActionCable client + live entry point.

**Result**: 4 commits, 76 tests, gates green, and a live verification that satisfied the
constitution's "actually connected at least once" gate:

```
[0.42s confirmed]  confirm_subscription
[161.85s broadcast] run/start   (testcases inline)
[162.89s broadcast] run/testcase index 0 passed
[162.96s broadcast] run/testcase index 1 passed
[162.97s broadcast] run/result  2/2
```

### Orchestration failures worth remembering

- **A dispatched prompt can sit unsubmitted in the worker's composer.** Worker B's injected
  task never started; `worker-read` showed only the banner. A bare Enter (`terminal send
  --text "" --enter`) submitted it. Same failure class as the 2026-08-04 session recorded in
  [[sources/2026-08-04-oss-workflow]] — it recurs, so check for it rather than waiting.
- **A worker's lifecycle capability can be lost while its work succeeds.** Worker B finished
  correctly but its `worker_done` was rejected with `dispatch_capability_invalid` (the
  manual re-prompt did not carry the capability token). The work was real; only the
  provenance was broken. Recovery: verify the artifacts directly, then `task-update
  --status completed` with a `coordinator_recovery` provenance note rather than pretending
  the report arrived.
- Low-level `orchestration dispatch --inject` is not visible to `worker-read`
  (`dispatch_not_found` — it has no worker record); read the terminal directly instead.

### Environment noise removed

`PostToolUse` hooks failed on every write with `Failed to connect to socket … cmux.sock`.
The user no longer uses cmux, so its Notification/PostToolUse hooks and the `Bash(cmux *)`
permission were removed from global settings.

---

## 2. Test-environment decisions (mid-flight, folded into #6)

1. Layer tests boot **no Spring context** — plain JUnit 5 + Kotest assertions + MockK;
   `@SpringBootTest` stays only in the single context-load test.
2. The default `test` task **excludes** `@Tag("integration")`; a separate `integrationTest`
   task includes it. `scripts/test.sh` stays unit + layer only.
3. Every normal-path and verdict-path parser test **must load a measured
   `fixtures/*.jsonl`** through a `FixtureLoader`; inline JSON only for cases that cannot
   have a capture (malformed JSON, synthetic boundaries).

→ [[decisions/2026-08-04-test-environment]]

---

## 3. Full-stack technology review

Prompted by the user pointing out that the stack had been chosen mechanically, not argued.

**Measured findings that changed decisions:**
- Spring Boot 3.5 hit **open-source EOL 2026-06-30**; 3.5.16 was the final OSS patch and all
  3.x branches are unsupported. Latest GA is 4.1.0 (Maven Central metadata; the search index
  reported a stale "latest").
- The **communication inventory** (2 inbound, 4 outbound, 3 local I/O) shows the async need
  is shaped per direction: inbound is request/response at human rates, and the only
  long-lived stream is the outbound observation socket.

**Rejected with reasons**: WebFlux for MCP (the Java SDK ships an MVC transport; ~1–5
concurrent agents), virtual-threads-everywhere (the WebSocket clients are callback APIs, so
it re-creates the glue Ktor removes), coroutines-everywhere, Ktor server / no framework,
Jackson-only serialization.

**Premise correction**: the constitution's Role section said "the user is a job-seeker". The
developer is employed and preparing an experienced-hire move; *users* of the tool may be
job-seekers. YAGNI stays, argued on engineering merit rather than time poverty.

→ [[decisions/2026-08-05-backend-stack]] · [[decisions/2026-08-05-hexagonal-architecture]]

---

## 4. Adversarial review of the communication inventory

The user asked for the same depth of scrutiny they had applied to the write-concurrency
question, applied to every entry. Two document-reading agents extracted measured facts from
`docs/programmers-protocol.md` and the design spec.

### Claims of mine that turned out to be assumptions

| Claim I stated | Reality |
|---|---|
| "`reject_subscription` is the measured cookie-expiry signal" | The protocol document never mentions `reject_subscription` at all. The **design** relied on it; nothing measured it. |
| "SQL terminates at `result_lesson_challenge`, algorithm at `finish`" | Termination is an **(action × type)** matrix. SQL *run* does send `finish`; algorithm *run* ends at `result` or `error`. |
| "Writes have no concurrency problem — there is a single writer" | Nothing in the design creates that property. Up to 8 subscriptions can complete at once against one JSONL, two read-modify-write state files, and one git index. |
| "Use an in-memory `Channel` with one consumer" | Superseded: the buffer dies with the process, and a missed grading is unrecoverable. |

### Highest-severity defects found

1. **Recording was gated on CodeFetch.** Both persistence steps sat after an HTTP fetch with
   no timeout, retry or failure branch — one failed fetch destroyed a verdict that has no
   other source (protocol §11).
2. **`error` was not a terminal frame**, so the measured cached-resubmission case
   (protocol §13.2) could never terminate, and the design's own "record the error too" line
   was unreachable code.
3. **LRU eviction vs the 30 s heartbeat** — "oldest" was undefined; either 8 open tabs pin
   the cache forever or an actively-grading tab is evicted.
4. **`ping` (3 s cadence) was discarded**, leaving no dead-connection detector at all.
5. **`attempt` had no defined source** — not a directory scan, not the JSONL, not a counter.

### The better answer to "should we add a queue?"

Yes, but not in memory. `development-rules` §2.4 already requires preserving raw frames, so
appending them first makes the **raw log itself the durable queue** — crash recovery becomes
a directory scan, and CodeFetch/git failures stop being able to destroy a verdict.
Serialization is dispatcher confinement rather than an actor, and the `attempt` race is
deleted (not guarded) by declaring the JSONL its single authority.

→ [[decisions/2026-08-05-capture-pipeline-stages]] ·
[[decisions/2026-08-05-write-serialization]] · [[decisions/2026-08-05-failure-taxonomy]]

---

## 5. Issue #10 — Spring Boot 4.1 + JVM 25 + version catalog

- Boot 3.5.16 → 4.1.0, `jvmToolchain(25)` (verified: class-file **major 69**)
- `gradle/libs.versions.toml` added; `spring.threads.virtual.enabled` turned on
- Gates green (76 tests), live capture re-verified end to end on the new stack

**The BOM check paid for itself.** Inspecting the 4.1.0 BOM before trusting it:

```
kotlin.version            = 2.3.21   (our compiler plugin is 2.4.10)
kotlin-coroutines.version = 1.10.2   (Ktor 3.5.2 bytecode needs 1.11.0)
```

Without the overrides the `NoSuchMethodError` from #6 would have returned. A version catalog
does **not** control BOM-managed versions.

### Observation: the socket closed silently after ~30 minutes

An idle observation run ended at **~30 m 50 s** with no exception, no close log and no
reconnect — the `Flow` completed and the process exited 0. The cause is **not established**
(server idle timeout, NAT, Wi-Fi, sleep are all candidates), so it is deliberately **not**
written into the protocol document. It does establish that the current client ends a session
with zero signal, which is a silent-gap defect.

A second incidental confirmation: testcases arrived **out of order** (index 1 before index
0), which is why the revised design requires a completeness check rather than a sort alone.
