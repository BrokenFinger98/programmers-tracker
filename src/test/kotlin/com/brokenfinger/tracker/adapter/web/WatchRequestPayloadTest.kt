package com.brokenfinger.tracker.adapter.web

import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.support.fixtures.aWatchBody
import com.brokenfinger.tracker.support.fixtures.aWatchCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Validation is the whole point of this parser: the extension reads DOM `data-*` attributes,
 * which are strings and vanish silently when Programmers changes its markup. A missing or
 * unparseable identifier must surface as a rejection, never as a substituted default
 * (CLAUDE.md · design §4.1).
 */
class WatchRequestPayloadTest {
    @Test
    fun `accepts the string identifiers the extension reads out of the DOM`() {
        WatchRequestPayload.parse(aWatchBody()) shouldBe aWatchCommand()
    }

    @Test
    fun `accepts the number form of the identifiers too`() {
        val body = aWatchBody(lessonId = "120804", challengeableId = "14643")

        WatchRequestPayload.parse(body) shouldBe aWatchCommand()
    }

    @Test
    fun `maps the database type onto the database problem kind`() {
        val body = aWatchBody(challengeableType = "\"database\"", lessonId = "131528")

        WatchRequestPayload.parse(body).kind shouldBe ProblemKind.DATABASE
    }

    @Test
    fun `matches the type case-insensitively and ignores surrounding whitespace`() {
        val body = aWatchBody(challengeableType = "\" Algorithm \"")

        WatchRequestPayload.parse(body).kind shouldBe ProblemKind.ALGORITHM
    }

    @Test
    fun `rejects an unknown type explicitly instead of guessing a channel`() {
        val body = aWatchBody(challengeableType = "\"sql\"")

        val thrown = shouldThrow<InvalidWatchRequestException> { WatchRequestPayload.parse(body) }

        thrown.field shouldBe "challengeableType"
        thrown.message!!.shouldContain("sql")
        thrown.message!!.shouldContain("algorithm")
        thrown.message!!.shouldContain("database")
    }

    @ParameterizedTest
    @ValueSource(strings = ["lessonId", "challengeableId", "challengeableType", "language", "codesKey"])
    fun `rejects a missing field, naming it`(field: String) {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(omit = setOf(field)))
        }

        thrown.field shouldBe field
        thrown.message!!.shouldContain("missing")
    }

    @Test
    fun `rejects an explicit null the same way as a missing field`() {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(codesKey = "null"))
        }

        thrown.field shouldBe "codesKey"
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"\"", "\"   \"", "{}", "[]", "true"])
    fun `rejects a language that is not a usable string`(raw: String) {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(language = raw))
        }

        thrown.field shouldBe "language"
    }

    @Test
    fun `rejects a blank codes key`() {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(codesKey = "\"  \""))
        }

        thrown.field shouldBe "codesKey"
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"abc\"", "\"12a\"", "\"\"", "\"12.5\"", "{}", "[]", "\"99999999999999999999\""])
    fun `rejects a lesson id that is not a whole number`(raw: String) {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(lessonId = raw))
        }

        thrown.field shouldBe "lessonId"
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"0\"", "\"-1\"", "0", "-14643"])
    fun `rejects a non-positive challengeable id`(raw: String) {
        val thrown = shouldThrow<InvalidWatchRequestException> {
            WatchRequestPayload.parse(aWatchBody(challengeableId = raw))
        }

        thrown.field shouldBe "challengeableId"
    }

    @ParameterizedTest
    @ValueSource(strings = ["{", "", "   ", "[]", "\"a string\"", "12"])
    fun `rejects a body that is not a JSON object`(body: String) {
        val thrown = shouldThrow<InvalidWatchRequestException> { WatchRequestPayload.parse(body) }

        thrown.field shouldBe null
    }
}
