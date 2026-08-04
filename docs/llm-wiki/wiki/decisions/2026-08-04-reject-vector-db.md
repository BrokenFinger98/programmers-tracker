---
type: decision
project: programmers-tracker
author: BrokenFinger98
tags: [architecture, vector-db, YAGNI]
created: 2026-08-04
updated: 2026-08-04
sources: [raw/sessions/2026-08-04-protocol-reverse-engineering-and-design.md]
---

# Vector DB · graph DB rejected

## Context
The suggestion: "wouldn't similarity search, a vector DB, or a knowledge graph be more effective?"

## Options considered
- **A. Adopt a vector DB** — embed problem statements and code for similar-problem search
- **B. Adopt a graph DB** — model prerequisite relations between concepts as a graph
- **C. Reject both, just preserve the data**

## Decision
**C.**

## Rationale
**Below scale threshold**: 689 problems, fewer than 2,000 expected submissions. All
embeddings fit in ~4 MB and exact search finishes in milliseconds. There is no reason to
sacrifice accuracy for approximate nearest-neighbor search.

**More fundamentally, embeddings capture the wrong axis.** In coding-test problems, the
surface narrative and the actual problem type are **deliberately decoupled**.

| Problem A | Problem B | Statement similarity | Actual type |
|---|---|---|---|
| Loading delivery boxes | Assigning meeting rooms | Low | Both greedy + sorting |
| Maze escape | Maze construction | High | BFS vs. implementation |

Since problem setters intentionally swap out the narrative, statement embeddings work
exactly backwards. **AI tagging is the more accurate similarity axis.**

Concept prerequisite relations form a fixed graph of a few dozen nodes, so a JSON file suffices.

## Accepted costs
- If external problem banks are merged and we reach tens of thousands of items, revisit
- If free-text retrospectives accumulate into the hundreds, semantic search may be missed

→ Since all original text (problem statements · code · errors · diffs) is preserved,
**an index can be built later if needed.** Indexes can always be regenerated; data never
recorded cannot be recovered.

## Outcome
_Not applicable_
