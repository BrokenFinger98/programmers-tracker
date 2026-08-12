package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.domain.calc.ProblemStatus
import com.brokenfinger.tracker.domain.calc.Since
import com.brokenfinger.tracker.domain.calc.TallyGroup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The tools this server exposes — three, and deliberately not the twenty of design §7.
 *
 * The rest of §7 is absent rather than stubbed: a tool that answered "not implemented"
 * would be worse than an absent one, because a client discovers it through `tools/list`
 * and plans around it. Exam state and a review schedule do not exist yet. The catalog and
 * the tag vocabulary now do — they ship in the jar — so `list_problems` is merely
 * unexposed (#100), not unsupported.
 *
 * Every description says what the tool *counts*, never what it concludes. Interpretation
 * belongs to the AI reading the numbers (CLAUDE.md, design §7).
 *
 * The order is fixed rather than derived from a map, because the specification asks for a
 * deterministic `tools/list` so clients can cache it.
 */
object McpToolCatalog {
    const val SUBMISSIONS = "submissions"
    const val GET_PROBLEM = "get_problem"
    const val STATS = "stats"
    const val LIST_PROBLEMS = "list_problems"
    const val REVIEW_QUEUE = "review_queue"
    const val SLOW_PASSES = "slow_passes"

    val NAMES = listOf(SUBMISSIONS, GET_PROBLEM, STATS, LIST_PROBLEMS, REVIEW_QUEUE, SLOW_PASSES)

    fun definitions(): JsonArray = buildJsonArray {
        add(submissions())
        add(getProblem())
        add(stats())
        add(listProblems())
        add(reviewQueue())
        add(slowPasses())
    }

    private fun slowPasses(): JsonObject = tool(
        name = SLOW_PASSES,
        title = "Passes slow enough to fail an efficiency test",
        description = "Every passed problem ranked by its slowest testcase, in milliseconds (design §6.5). " +
            "Passing is not the end: a solution far slower than its neighbours missed the intended approach, " +
            "and a real exam's efficiency tests score that as an outright fail. **No baseline is applied** — " +
            "the design asks for a comparison against same-tag, same-level problems and there are not enough " +
            "recorded passes to have peers, so the whole distribution comes back in one call and where the " +
            "line falls is yours to decide. Each item carries the level, tags and language a fair comparison " +
            "needs. **Compare within one language.** Across languages the ordering is mostly runtime " +
            "startup, not solution quality: one measured problem whose answer is a single statement in " +
            "every language ranks java at 83.33 ms and cpp at 1.12 ms, a 74× spread with nothing in it " +
            "about the code. `untimed` counts passes with no reading at all: SQL sends no per-case timing, " +
            "and a runtime error or timeout drops it case by case — those are excluded from the ranking " +
            "rather than ranked as instant.",
    ) {
        putJsonObject("properties") {
            putJsonObject("thresholdMs") {
                put("type", "number")
                put("exclusiveMinimum", 0)
                put("description", "Keep only passes whose slowest testcase reached this many milliseconds.")
            }
        }
    }

    private fun reviewQueue(): JsonObject = tool(
        name = REVIEW_QUEUE,
        title = "Problems due for re-solving",
        description = "Spaced repetition over your own passes (design §6.4). Programmers knows only that a " +
            "problem was solved; these records know how — the submits it took and whether the questions tab " +
            "was open while you were stuck — and that is what sets the interval. Most overdue first. " +
            "**The inputs come back with each item so you can disagree with the schedule**: this server " +
            "computes a date, it does not judge the learner. `sawQuestions` is absent when no browser " +
            "extension was watching, which is not the same as false. `focusedSec` is reported and never " +
            "scored — calibrating it needs a per-level distribution of how long problems take, which does " +
            "not exist yet, so weigh it yourself. Only problems with a recorded pass appear; one solved " +
            "before this tool existed has no record here at all.",
    ) {
        putJsonObject("properties") {
            putJsonObject("limit") {
                put("type", "integer")
                put("minimum", 1)
                put("description", "Keep only this many, from the most overdue end.")
            }
        }
    }

    private fun listProblems(): JsonObject = tool(
        name = LIST_PROBLEMS,
        title = "The problem catalog, with your standing against each",
        description = "The shipped Programmers catalog joined against the records: every problem carries " +
            "`status` — untouched, attempted or passed — and the number of submits. `untouched` is the " +
            "answer no other tool can give, because the records alone cannot tell \"never tried\" from " +
            "\"tried and failed\". The catalog is a snapshot we do not own, so a problem published after " +
            "it was built is simply absent.",
    ) {
        putJsonObject("properties") {
            putJsonObject("level") {
                put("type", "integer")
                put("description", "Keep only problems at this Programmers level.")
            }
            putJsonObject("part") {
                put("type", "string")
                put("description", "Keep only problems in this part, matched case-insensitively and in full.")
            }
            putJsonObject("tag") {
                put("type", "string")
                put("description", "Keep only problems carrying this solved.ac tag, matched case-insensitively.")
            }
            putJsonObject("status") {
                put("type", "string")
                put("description", "Keep only problems you stand this way against.")
                putJsonArray("enum") { ProblemStatus.entries.forEach { add(it.wireName()) } }
            }
        }
    }

    private fun submissions(): JsonObject = tool(
        name = SUBMISSIONS,
        title = "Submission history",
        description = "Every recorded run and submit, newest first, optionally narrowed by date or verdict. " +
            "Returns the stored records themselves; per-testcase detail, compiler output and diffs are " +
            "omitted here and available from get_problem. A field that was never recorded is absent." +
            ELAPSED_MEANS,
    ) {
        putJsonObject("properties") {
            putJsonObject("since") {
                put("type", "string")
                put("description", Since.FORMAT + ". A bare date is read in the record's own UTC offset.")
            }
            putJsonObject("verdict") {
                put("type", "string")
                put("description", "Keep only submissions the judge resolved to this verdict.")
                putJsonArray("enum") { Verdict.entries.forEach { add(it.name) } }
            }
        }
    }

    private fun getProblem(): JsonObject = tool(
        name = GET_PROBLEM,
        title = "One problem and its attempts",
        description = "Everything recorded against one Programmers lesson: catalog metadata as captured, and " +
            "every submission in full, including per-testcase results and compiler output. A lesson with " +
            "nothing recorded answers with an empty history rather than an error — we report what we " +
            "observed, which may be nothing." + ELAPSED_MEANS,
    ) {
        putJsonObject("properties") {
            putJsonObject("lessonId") {
                put("type", "integer")
                put("description", "The Programmers lesson id, as it appears in the problem page URL.")
            }
        }
        putJsonArray("required") { add("lessonId") }
    }

    private fun stats(): JsonObject = tool(
        name = STATS,
        title = "Counts by group",
        description = "Counts submissions per bucket. Counts only — it ranks nothing and concludes nothing. " +
            "**Submits, never runs**: pressing Run is how code gets written, not an attempt at the problem, " +
            "so its verdict is not counted here. `submissions` returns both and marks each with its `action`. " +
            "An entry with no `key` counts the submissions whose grouping value was never recorded, which " +
            "is not the same as a bucket named unknown. **`problem` counts across languages**: one problem " +
            "solved in Java and again in Kotlin is one bucket here and two items in `review_queue` and " +
            "`slow_passes`, which key on the pair. Neither is wrong — a submission count per problem is the " +
            "question `problem` answers — but reading the two side by side without knowing it looks like a " +
            "disagreement. Group by `language` for the other axis.",
    ) {
        putJsonObject("properties") {
            putJsonObject("groupBy") {
                put("type", "string")
                put("description", "What to count by.")
                putJsonArray("enum") { TallyGroup.wireNames().forEach { add(it) } }
            }
        }
        putJsonArray("required") { add("groupBy") }
    }

    // `additionalProperties: false` on every schema: an argument we do not understand is a
    // client bug or a stale tool list, and silently ignoring it would answer a question
    // narrower than the one that was asked.
    /**
     * Appended to every description rather than repeated in every answer (#187). A client
     * receives this once from `tools/list`; the results carry counts.
     */
    /**
     * Appended to the descriptions of the tools that return records. `elapsedSec` reads as time
     * on task and is wall clock; a measured record carries 77251 beside a `focusedSec` of 37
     * (#205), and an answer with no explanation invites exactly the wrong conclusion.
     */
    const val ELAPSED_MEANS: String =
        " `elapsedSec` is **wall clock since the problem was first opened** — sleep, other work " +
            "and days between sessions included — not time on task. One measured record carries " +
            "`elapsedSec: 77251` beside `sensor.focusedSec: 37`: half a minute of work on a tab " +
            "left open overnight. Use `focusedSec` for effort and treat `elapsedSec` as calendar " +
            "time from first encounter; they differ by orders of magnitude and neither is wrong."

    const val INCOMPLETE_HISTORY: String =
        " If `incompleteHistory` is present in an answer, gradings were captured that no record " +
            "represents — every tool here reads that same history, so the answer is drawn over a " +
            "record with holes and any conclusion from it must say so. They are not recoverable: " +
            "the missing `start` frame carries the testcase ids and the problem's examples, and " +
            "pairing them with an attempt would be a guess."

    private fun tool(name: String, title: String, description: String, schema: JsonObjectBuilderScope): JsonObject =
        buildJsonObject {
            put("name", name)
            put("title", title)
            put("description", description + INCOMPLETE_HISTORY)
            putJsonObject("inputSchema") {
                put("type", "object")
                schema()
                put("additionalProperties", false)
            }
        }
}

private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
