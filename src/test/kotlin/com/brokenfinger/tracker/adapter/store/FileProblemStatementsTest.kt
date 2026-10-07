package com.brokenfinger.tracker.adapter.store

import com.brokenfinger.tracker.support.fixtures.A_PUSH_TOKEN_LINE
import com.brokenfinger.tracker.support.fixtures.aLink
import com.brokenfinger.tracker.support.fixtures.aPushTokenIn
import com.brokenfinger.tracker.support.fixtures.canPlantLinksIn
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reading back what [FileDerivedArtifacts] wrote (#278). The point of storing the statement was
 * that an AI could see what the problem asked, and it could not until this existed.
 */
class FileProblemStatementsTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `answers with the statement stored beside the records`() {
        write("the problem, as Programmers worded it\n")

        statements().of(120804, "두 수의 곱 구하기") shouldBe "the problem, as Programmers worded it"
    }

    /** A problem captured before #275, or a page that carried none. */
    @Test
    fun `a problem with no statement is absent rather than empty`() {
        statements().of(120804, "두 수의 곱 구하기").shouldBeNull()
    }

    /**
     * A statement is the one thing on this surface a reader can do without, so an unreadable
     * file must not take the rest of `get_problem` down with it.
     */
    @Test
    fun `a file that cannot be read is absent, not an error`() {
        val file = RecordLayout(root).statementFile(120804, "두 수의 곱 구하기")
        Files.createDirectories(file)

        statements().of(120804, "두 수의 곱 구하기").shouldBeNull()
    }

    @Test
    fun `a file holding only whitespace is absent too`() {
        write("   \n\n")

        statements().of(120804, "두 수의 곱 구하기").shouldBeNull()
    }

    /**
     * The statement goes to `get_problem` as it is read, and the root holds the push token beside
     * `problems/`. A link that leads there, cloned or pulled in, returned the token (#354).
     */
    @Test
    fun `a statement that is a link to the push token is absent`() {
        assumeTrue(canPlantLinksIn(root), "this test makes symbolic links")
        val statement = RecordLayout(root).statementFile(120804, "두 수의 곱 구하기")
        aLink(statement, aPushTokenIn(root))

        Files.readString(statement) shouldContain A_PUSH_TOKEN_LINE
        statements().of(120804, "두 수의 곱 구하기").shouldBeNull()
    }

    private fun write(text: String) {
        val file = RecordLayout(root).statementFile(120804, "두 수의 곱 구하기")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun statements() = FileProblemStatements(RecordLayout(root))
}
