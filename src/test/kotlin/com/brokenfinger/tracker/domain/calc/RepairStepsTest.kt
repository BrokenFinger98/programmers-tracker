package com.brokenfinger.tracker.domain.calc

import com.brokenfinger.tracker.domain.Outcome
import com.brokenfinger.tracker.domain.Verdict
import com.brokenfinger.tracker.support.fixtures.aCodedGrading
import com.brokenfinger.tracker.support.fixtures.aRun
import com.brokenfinger.tracker.support.fixtures.aSubmit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Zero mocks (dev rules §3, §6.1). The cases spec 2026-10-07 §6 names for 4.3 — failed→passed,
 * failed→failed, unknown code on either side, repeated identical runs, run→submit across actions —
 * and the late-attachment case §4.2 added.
 */
class RepairStepsTest {
    @Test
    fun `a failed run followed by a passing run is one step, with the diff between their code`() {
        val failed = aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "select a\nfrom t")
        val passed = aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "select b\nfrom t")

        val step = RepairSteps.of(listOf(failed, passed)).single()

        step.from shouldBe failed
        step.to shouldBe passed
        step.diff shouldBe "--- a/from\n+++ b/to\n@@ -1,2 +1,2 @@\n-select a\n+select b\n from t"
        step.noDiff.shouldBeNull()
    }

    @Test
    fun `each failure followed by a change is its own step`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = "b"),
            aCodedGrading(aRun(at = T2, verdict = Verdict.WRONG), code = "c"),
        )

        RepairSteps.of(timeline).map { it.to.code?.text } shouldContainExactly listOf("b", "c")
    }

    @Test
    fun `a passing grading starts no step`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.PASS), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = "b"),
        )

        RepairSteps.of(timeline).shouldBeEmpty()
    }

    /** D3: 25 of 65 measured candidate steps start at a grading no verdict was resolved for. */
    @Test
    fun `an unresolved grading starts a step, because unresolved is not passed`() {
        val unresolved = aCodedGrading(aRun(at = T0, verdict = null, outcome = Outcome.UNKNOWN), code = "a")
        val next = aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "b")

        RepairSteps.of(listOf(unresolved, next)).single().from shouldBe unresolved
    }

    @Test
    fun `repeated identical runs make no step — nothing was corrected`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aRun(at = T2, verdict = Verdict.PASS), code = "b"),
        )

        val step = RepairSteps.of(timeline).single()

        step.from shouldBe timeline[1]
    }

    /** runs.jsonl keeps code as fetched; attempts/ keeps it with one trailing newline (D4). */
    @Test
    fun `code that differs only by trailing newlines is identical`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aSubmit(at = T1, verdict = Verdict.WRONG), code = "a\n"),
        )

        RepairSteps.of(timeline).shouldBeEmpty()
    }

    @Test
    fun `a step whose earlier code is unknown has no diff and says so`() {
        val step = RepairSteps.of(
            listOf(
                aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = null),
                aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "b"),
            ),
        ).single()

        step.diff.shouldBeNull()
        step.noDiff shouldBe NoDiff.FROM_CODE_UNKNOWN
    }

    @Test
    fun `a step whose later code is unknown has no diff and says so`() {
        val step = RepairSteps.of(
            listOf(
                aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
                aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = null),
            ),
        ).single()

        step.noDiff shouldBe NoDiff.TO_CODE_UNKNOWN
    }

    /** Spec §4.3: "never dropped and never paired across the gap". */
    @Test
    fun `a gap in the code is never bridged`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = null),
            aCodedGrading(aRun(at = T2, verdict = Verdict.PASS), code = "b"),
        )

        RepairSteps.of(timeline).map { it.noDiff } shouldContainExactly
            listOf(NoDiff.TO_CODE_UNKNOWN, NoDiff.FROM_CODE_UNKNOWN)
    }

    @Test
    fun `both sides unknown is one reason, not two`() {
        val step = RepairSteps.of(
            listOf(
                aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = null),
                aCodedGrading(aRun(at = T1, verdict = Verdict.WRONG), code = null),
            ),
        ).single()

        step.noDiff shouldBe NoDiff.CODE_UNKNOWN
    }

    @Test
    fun `a failed run followed by a submit is a step across actions`() {
        val run = aCodedGrading(aRun(at = T0, verdict = Verdict.COMPILE_ERROR), code = "a")
        val submit = aCodedGrading(aSubmit(at = T1, verdict = Verdict.PASS), code = "b")

        RepairSteps.of(listOf(run, submit)).single().to shouldBe submit
    }

    @Test
    fun `each language is paired with itself`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG, language = "java"), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.PASS, language = "kotlin"), code = "k"),
            aCodedGrading(aRun(at = T2, verdict = Verdict.PASS, language = "java"), code = "b"),
        )

        val step = RepairSteps.of(timeline).single()

        step.from shouldBe timeline[0]
        step.to shouldBe timeline[2]
    }

    /** D4: a late side may hold the later grading's code, so "identical" proves nothing there. */
    @Test
    fun `identical code beside a late side is still a step`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "b", late = true),
            aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "b"),
        )

        RepairSteps.of(timeline).single().noDiff shouldBe NoDiff.SAME_CODE
    }

    @Test
    fun `code too large to diff says so`() {
        val huge = (0..UnifiedDiff.MAX_INPUT_LINES).joinToString("\n") { "x$it" }
        val step = RepairSteps.of(
            listOf(
                aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = huge),
                aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "b"),
            ),
        ).single()

        step.noDiff shouldBe NoDiff.TOO_LARGE
    }

    /** An empty editor is no lines, not one blank line, so the diff is only the lines written. */
    @Test
    fun `code written into an empty editor diffs as added lines`() {
        val step = RepairSteps.of(
            listOf(
                aCodedGrading(aRun(at = T0, verdict = Verdict.WRONG), code = "\n"),
                aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "b"),
            ),
        ).single()

        step.diff shouldBe "--- a/from\n+++ b/to\n@@ -0,0 +1,1 @@\n+b"
    }

    /** `get_problem(include=runs)` reads every transition, passing starts and unchanged code included. */
    @Test
    fun `transitions keep what steps leave out`() {
        val timeline = listOf(
            aCodedGrading(aRun(at = T0, verdict = Verdict.PASS), code = "a"),
            aCodedGrading(aRun(at = T1, verdict = Verdict.PASS), code = "a"),
        )

        RepairSteps.transitions(timeline).single().noDiff shouldBe NoDiff.SAME_CODE
    }

    @Test
    fun `no-diff reasons have wire names`() {
        NoDiff.entries.map { it.wireName() } shouldContainExactly
            listOf("fromCodeUnknown", "toCodeUnknown", "codeUnknown", "sameCode", "tooLarge")
    }

    private companion object {
        const val T0 = "2026-10-07T10:00:00+09:00"
        const val T1 = "2026-10-07T10:00:05+09:00"
        const val T2 = "2026-10-07T10:00:10+09:00"
    }
}
