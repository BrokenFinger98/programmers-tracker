package com.brokenfinger.tracker.adapter.git

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** One object as `git cat-file` describes it, and the lines that do not describe one (#373). */
class GitObjectTest {
    @Test
    fun `a batch-check line is an object`() {
        GitObject.ofReceived("$ID blob 12") shouldBe GitObject(ID, "blob", 12)
    }

    @Test
    fun `its header is the line cat-file --batch prints before its content`() {
        GitObject(ID, "tree", 0).header() shouldBe "$ID tree 0"
    }

    /** Nothing git did not say is made up: each of these refuses the search that asked. */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "$ID missing", "$ID ambiguous", "$ID blob", "$ID blob 12 extra", "$ID blob -1", "$ID blob twelve",
            "$ID  12", "$ID note 12", "",
        ],
    )
    fun `a line that is not an object's is none`(line: String) {
        GitObject.ofReceived(line) shouldBe null
    }

    private companion object {
        const val ID = "0123456789abcdef0123456789abcdef01234567"
    }
}
