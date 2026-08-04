---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [tags, taxonomy, solved.ac]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Tag vocabulary is the solved.ac 180-tag set

## Context
Analyzing weaknesses by type requires algorithm tags on problems. But **Programmers does
not publish per-problem tags.** Of the 689 `partTitle` values only 47 (7%) are algorithm
types; the rest are contest names and difficulty groupings.

## Options considered
- **A. Our own tag taxonomy** — define one ourselves around real coding-test types
- **B. Adopt the solved.ac vocabulary** — use the external taxonomy as-is
- **C. Programmers' internal categories** — collect values leaking from the recommendation API

## Decision
**B.** Adopt the 180 solved.ac tags as the vocabulary and have the AI read and tag each problem.

## Rationale
We built a 17-item list for A, then discarded it after it was pointed out that **KMP, LIS,
Fenwick tree, trie, topological sort, combinatorics, and divide-and-conquer were all
missing**. A hand-made list is always incomplete.

The solved.ac vocabulary is complete (180 tags), hierarchical (`그래프 이론 > 그래프 탐색 > BFS`,
i.e. graph theory > graph traversal > BFS), and Korean-native. Zero maintenance burden.

C was infeasible: the recommendation API returns only one problem at a time and repeats
the same value on repeated calls, so full collection was impossible.

## Accepted costs
- **An external dependency appears.** Baekjoon Online Judge shut down in May 2026, and no
  one knows how long solved.ac will last
  → mitigated by pinning the `.ps/tag-vocab.json` snapshot. Even if the origin disappears,
  tagging and aggregation keep working
- Because of the Cloudflare challenge, collection must be done once in a browser context

## Outcome
Measured 2026-08-04: 180 tags collected successfully; cross-checked against tags of 210 Baekjoon problems.
