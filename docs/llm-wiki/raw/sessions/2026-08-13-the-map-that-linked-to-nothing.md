# 2026-08-13 — the map that linked to nothing

Raw session record. Immutable (wiki schema §1).

Continuation of the tag-map work. Three defects in one feature, in one night, each found by a
different question — and none of them by a test that already existed.

---

## The design was right and its premise was unstated

#229 shipped. The owner opened the vault and sent a screenshot: **81 of 83 tags isolated.** A
dust cloud.

The ADR's own argument was that an isolated node is the finding. It holds **only when isolation
is rare.** With four problems recorded, near everything was isolated and isolation carried
nothing at all. Nothing in the reasoning was false; it assumed a vault with history and never
said so.

The fix was measured before any code was written — 255 co-occurring pairs over 83 tags, zero
tags co-occurring with nothing, highest degree 27 rather than 82. So no threshold was needed,
which matters because a threshold would have been a judgement and the whole ADR is about not
making those.

**It was shown before it was built.** A self-contained preview drawn from the real catalog let
the owner see both states and say yes. That is the first time in this project a design decision
was made against a picture rather than a description.

---

## The preview had its own bug, and it was mine

The first version oscillated violently. The centering force was scaled by viewport size:

```js
const k = Math.min(W, H) * 0.00042;   // ≈ 0.34 at 800px
a.vx += (W / 2 - a.x) * k;            // a node 300px off-centre moves 100px in one frame
```

A constant became a coefficient, so the system diverged, and there was no alpha decay to settle
it. A constant and a cooling schedule fixed it.

Worth separating: **a preview's bugs are not the design's.** But it cost the owner a confusing
first look at a picture that was supposed to be the clear part.

---

## Then: 43 of 83 links resolved to nothing

Found while waiting on CI, by asking a question nothing else had: *does an emitted link name a
file that exists?*

`RecordLayout.slugOf` turns `binary_search` into `binary-search.md`. Both writers built the
wikilink from the **tag**. Every tag with an underscore — 43 of 83 — linked to a file that does
not exist, so Obsidian would draw a ghost node instead of an edge, in a feature whose entire
purpose is the edges.

### The test had pinned it, and every word of the test was true

> `a tag the filesystem would not take keeps its spelling in the field`

The `tag:` field is the datum and must stay verbatim. The file name is a path and is slugged.
They differ on purpose. The spec said the same thing in the same words.

And none of it answers *what a link needs*. Code, test and spec agreed with each other; only the
filesystem disagreed, and nothing asked it.

This is a new variant for [[concepts/tests-that-explain-defects]]. The instances recorded there
are wrong explanations of right observations. This is a **right explanation of an incomplete
observation** — harder to see, because a reviewer checking the claim finds it true.

The separation is the question's direction. *"Do the two names differ?"* restates the rule, and a
test that restates a rule agrees with whatever the rule currently is. *"Does this link name a
file that exists?"* asks the world. Only the second one failed.

**I had cited that page twice earlier the same day.** Knowing the pattern is not protection from
it.

---

## Three defects, three different questions

| defect | what found it |
|---|---|
| the map had no shape | the owner opened it and looked |
| the preview oscillated | the owner said it was shaking |
| 43 links resolved to nothing | asking whether a link names a file that exists |

Not one of them came from the suite, and the suite was green throughout. The pattern across all
three is the same as the day before: **every check I ran agreed with the code, because I wrote
both.** What broke them was an outside reference — a screenshot, a complaint, a directory
listing.
