---
type: source
project: programmers-tracker
tags: [vault, testing, measurement, failed-attempts, obsidian]
created: 2026-08-13
updated: 2026-08-13
sources: [raw/sessions/2026-08-13-the-map-that-linked-to-nothing.md]
---

# 2026-08-13 session summary — three defects in one feature, none found by a test

## Key claims

1. **A decision can be right and its premise unstated.** "An isolated node is the finding" holds
   only when isolation is rare; with four problems recorded, 81 of 83 tags were isolated and
   isolation carried nothing. The ADR assumed a vault with history and never said so.
2. The fix was **measured before it was written**: 255 co-occurring pairs, zero tags with no
   neighbour, highest degree 27. So no threshold was needed — which matters, because a threshold
   would have been the judgement the whole decision exists to avoid.
3. **It was shown before it was built.** A preview from the real catalog let the owner decide
   against a picture rather than a description — the first time in this project.
4. The preview oscillated because a constant had become a coefficient (`min(W,H) * 0.00042`).
   **A preview's bugs are not the design's**, but they still cost a confusing first look.
5. **43 of 83 tags could not be linked to.** Both writers built the wikilink from the tag while
   the file name is slugged, so a link to any underscored tag named a file that does not exist.
   Latent rather than live: the vault held 4 links, none to a slugged tag, and the edges #232
   adds would have dangled 178 times out of 510.
6. **The test that should have caught it was entirely true.** It pinned that the field keeps the
   tag's spelling and the file name does not — correct, and silent on what a *link* needs. A new
   variant for [[concepts/tests-that-explain-defects]]: a right explanation of an incomplete
   observation, which no reviewer catches by checking the claim.
7. **Assert against the artifact, not the rule that produced it.** A rule and its restatement
   cannot disagree; a rule and the filesystem can.
8. None of the three defects came from the suite, which stayed green. Each was found by an
   outside reference: a screenshot, a complaint, a directory listing.

## Pages this source updated

[[concepts/tests-that-explain-defects]] ·
[[decisions/2026-08-12-the-server-counts-and-names-nothing]]
