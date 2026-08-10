package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.support.fixtures.aWatchBody
import com.brokenfinger.tracker.support.fixtures.aWatchCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Validation is the whole point of this parser: a lesson id read from a DOM attribute or a
 * URL is a string, and it vanishes silently when Programmers changes its markup. A missing
 * or unparseable field must surface as a rejection, never as a substituted default
 * (CLAUDE.md · design §4.1).
 *
 * Two fields since #114. The channel identifiers are the problem's own and the server reads
 * them off its page, so there is nothing here to validate about them — and correspondingly
 * nothing for a caller to get wrong.
 */
class WatchRequestPayloadTest {
    @Test
    fun `accepts the string form a DOM attribute or URL produces`() {
        WatchRequestPayload.parse(aWatchBody()) shouldBe aWatchCommand()
    }

    @Test
    fun `accepts the number form a hand-typed request produces`() {
        WatchRequestPayload.parse(aWatchBody(lessonId = "120804")) shouldBe aWatchCommand()
    }

    /**
     * The identifiers a caller used to have to send are now ignored rather than rejected: an
     * older extension build still posting five fields keeps working, and its two useful ones
     * are read as before.
     */
    @Test
    fun `tolerates the fields the old contract required`() {
        val body = aWatchBody(
            extra = mapOf(
                "challengeableId" to "\"14643\"",
                "challengeableType" to "\"algorithm\"",
                "codesKey" to "\"49598\"",
            ),
        )

        WatchRequestPayload.parse(body) shouldBe aWatchCommand()
    }

    @ParameterizedTest
    @ValueSource(strings = ["lessonId", "language"])
    fun `rejects a missing field, naming it`(field: String) {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(omit = setOf(field)))
        }

        thrown.field shouldBe field
        thrown.message shouldContain "missing"
    }

    /** An explicit null is a field the sensor could not read, not a field it chose to omit. */
    @Test
    fun `rejects an explicit null, naming it`() {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(language = "null"))
        }

        thrown.field shouldBe "language"
        thrown.message shouldContain "null"
    }

    @Test
    fun `rejects a blank language`() {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(language = "\"  \""))
        }

        thrown.field shouldBe "language"
        thrown.message shouldContain "blank"
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"0\"", "\"-1\"", "\"abc\"", "\"12.5\"", "true"])
    fun `rejects a lesson id that is not a positive whole number`(raw: String) {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(lessonId = raw))
        }

        thrown.field shouldBe "lessonId"
    }

    @Test
    fun `rejects a body that is not JSON at all`() {
        val thrown = shouldThrow<InvalidWatchRequestException> { WatchRequestPayload.parse("not json") }

        thrown.message shouldContain "valid JSON"
    }

    @Test
    fun `rejects an empty body`() {
        val thrown = shouldThrow<InvalidWatchRequestException> { WatchRequestPayload.parse("") }

        thrown.message shouldContain "missing"
    }
}
