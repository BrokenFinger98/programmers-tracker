package com.brokenfinger.tracker.adapter.mcp

import com.brokenfinger.tracker.domain.calc.Since
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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
    fun `every argument narrows the repair_steps call in the order the slash command takes them`() {
        val scope = ExamPrepScope(language = "java", since = "2026-09-01", part = "SELECT")

        scope.repairStepsCall() shouldBe "repair_steps(language=\"java\", since=\"2026-09-01\", part=\"SELECT\")"
    }

    @Test
    fun `the paragraph names the scope and says only repair_steps takes it`() {
        val paragraph = ExamPrepScope(language = "mysql", since = "2026-09-01").paragraph()

        paragraph shouldContain "Scope: language \"mysql\", since \"2026-09-01\"."
        paragraph shouldContain "Only repair_steps takes this scope"
    }

    /** Claude Code splits arguments on spaces: "GROUP BY" arrives as "GROUP" (38 of 49 parts have a space). */
    @Test
    fun `a given part comes with the warning that it may be cut short`() {
        val paragraph = ExamPrepScope(part = "GROUP").paragraph()

        paragraph shouldContain "part \"GROUP\""
        paragraph shouldContain "split arguments on spaces"
        paragraph shouldContain "Match it against the part keys stats(groupBy=part) returns"
        paragraph shouldContain "call repair_steps with the full name"
        paragraph shouldContain "say which part you used"
    }

    @Test
    fun `no part, no warning about parts`() {
        ExamPrepScope(language = "java").paragraph() shouldNotContain "split arguments on spaces"
    }

    @Test
    fun `a since the tools would refuse is refused here, in their words, with the order named`() {
        val refused = shouldThrow<IllegalArgumentException> { ExamPrepScope(since = "yesterday") }

        refused.message shouldStartWith Since.FORMAT
        refused.message shouldContain "positional — language, since, part"
    }

    @Test
    fun `an offset date-time is a since the tools take, so it is taken`() {
        val call = ExamPrepScope(since = "2026-09-01T09:00:00+09:00").repairStepsCall()

        call shouldContain "since=\"2026-09-01T09:00:00+09:00\""
    }

    /** A part is matched by the tools, not here: checking it would refuse what Claude Code sends. */
    @Test
    fun `language and part are taken as typed, whatever they say`() {
        val scope = ExamPrepScope(language = "fortran", part = "코딩")

        scope.repairStepsCall() shouldBe "repair_steps(language=\"fortran\", part=\"코딩\")"
    }

    /** `/exam_prep 2026-09-01` puts the date where `language` goes; refused, it says why. */
    @Test
    fun `a language that reads as a date is refused as a positional slip`() {
        val refused = shouldThrow<IllegalArgumentException> { ExamPrepScope(language = "2026-09-01") }

        refused.message shouldContain "positional — language, since, part"
    }

    /** "String, Date" is a real part name: unquoted, a model would read it as two arguments. */
    @Test
    fun `a part name with a comma stays one value in the call`() {
        ExamPrepScope(part = "String, Date").repairStepsCall() shouldBe "repair_steps(part=\"String, Date\")"
    }

    /** Programmers' id is `python3`, so "python" is taken as typed and answers empty — and empty is not clean. */
    @Test
    fun `whenever something narrows, an empty answer is said not to be clean`() {
        val sentence = "An empty answer under this scope is not an absence of mistakes"

        ExamPrepScope(language = "python").paragraph() shouldContain sentence
        ExamPrepScope().paragraph() shouldNotContain sentence
    }

    /** `/exam_prep 2026-09-01 java` types the date first: it is the order that is wrong, not the second value. */
    @Test
    fun `a date typed first is refused for its order, not its format`() {
        val refused = shouldThrow<IllegalArgumentException> { ExamPrepScope(language = "2026-09-01", since = "java") }

        refused.message shouldContain "language \"2026-09-01\" reads as a date"
    }
}
