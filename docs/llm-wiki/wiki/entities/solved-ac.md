---
type: entity
project: programmers-tracker
tags: [solved.ac, tags, external-dependency]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# solved.ac

A community service providing algorithm tags and tiers for Baekjoon problems.
**Baekjoon Online Judge shut down in May 2026, but the solved.ac API is still alive** (measured 2026-08-04).

- `GET /api/v3/tag/list?page=N` — the 180-tag vocabulary
- `GET /api/v3/problem/lookup?problemIds=…` — batch lookup, 100 at a time
- Blocked for pure HTTP clients by a **Cloudflare challenge**. A browser context is required

We use **the classification vocabulary, not the judge**, so the Baekjoon shutdown does not
affect us. Still, as an external dependency it is pinned as the `.ps/tag-vocab.json` snapshot.

→ [[decisions/2026-08-04-solved-ac-tag-vocabulary]]
