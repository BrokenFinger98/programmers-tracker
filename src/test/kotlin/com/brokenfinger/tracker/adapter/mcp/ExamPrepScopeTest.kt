package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Zero mocks: a scope is three optional strings and the words they turn into. */
class ExamPrepScopeTest {
    @Test
    fun `no argument means everything on record and an unnarrowed call`() {
        val scope = ExamPrepScope()

        scope.paragraph() shouldBe "Scope: everything on record."
        scope.repairStepsCall() shouldBe "repair_steps()"
    }

    @Test
    fun `every argument narrows the repair_steps call in the order the tool documents them`() {
        val scope = ExamPrepScope(language = "java", since = "2026-09-01", part = "SELECT")

        scope.repairStepsCall() shouldBe "repair_steps(language=java, since=2026-09-01, part=SELECT)"
    }

    @Test
    fun `the paragraph names the scope and says only repair_steps takes it`() {
        val paragraph = ExamPrepScope(language = "mysql", since = "2026-09-01").paragraph()

        paragraph shouldContain "Scope: language mysql, since 2026-09-01."
        paragraph shouldContain "Only repair_steps takes this scope"
    }

    /** Claude Code splits arguments on spaces: "GROUP BY" arrives as "GROUP" (38 of 49 parts have a space). */
    @Test
    fun `a given part comes with the warning that it may be cut short`() {
        val paragraph = ExamPrepScope(part = "GROUP").paragraph()

        paragraph shouldContain "part \"GROUP\""
        paragraph shouldContain "split arguments on spaces"
        paragraph shouldContain "say which part you used"
    }

    @Test
    fun `no part, no warning about parts`() {
        ExamPrepScope(language = "java").paragraph() shouldNotContain "split arguments on spaces"
    }

    @Test
    fun `a since the tools would refuse is refused here, in their words`() {
        val refused = shouldThrow<IllegalArgumentException> { ExamPrepScope(since = "yesterday") }

        refused.message shouldBe Since.FORMAT
    }

    @Test
    fun `an offset date-time is a since the tools take, so it is taken`() {
        val call = ExamPrepScope(since = "2026-09-01T09:00:00+09:00").repairStepsCall()

        call shouldContain "since=2026-09-01T09:00:00+09:00"
    }

    /** A part is matched by the tools, not here: checking it would refuse what Claude Code sends. */
    @Test
    fun `language and part are taken as typed, whatever they say`() {
        val scope = ExamPrepScope(language = "fortran", part = "코딩")

        scope.repairStepsCall() shouldBe "repair_steps(language=fortran, part=코딩)"
    }

    /** `/exam_prep 2026-09-01` puts the date where `language` goes; refused, it says why. */
    @Test
    fun `a language that reads as a date is refused as a positional slip`() {
        val refused = shouldThrow<IllegalArgumentException> { ExamPrepScope(language = "2026-09-01") }

        refused.message shouldContain "positional — language, since, part"
    }
}
