package com.brokenfinger.tracker.protocol.message

import com.brokenfinger.tracker.support.fixtures.FixtureLoader
import com.brokenfinger.tracker.support.fixtures.aStartMessage
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/** Failure paths — protocol doc §7 and verification log #9–#13. */
class SubmitMessageFailureTest {
    private val runErrorStream = FixtureLoader.messages("algorithm-run-error.jsonl")

    // Timeout: run_time/memory_size are null (protocol doc §7).
    @Test
    fun `timeout testcases carry null run metrics`() {
        failedTestcases("algorithm-timeout.jsonl").forAll {
            it.msg shouldBe "실패 (시간 초과)"
            it.passed shouldBe false
            it.runTime shouldBe null
            it.memorySize shouldBe null
        }
    }

    @Test
    fun `runtime error testcases carry null run metrics`() {
        failedTestcases("algorithm-runtime.jsonl").forAll {
            it.msg shouldBe "실패 (런타임 에러)"
            it.runTime shouldBe null
            it.memorySize shouldBe null
        }
    }

    // Compile errors are indistinguishable from runtime errors on the submit path (protocol doc §7).
    @Test
    fun `compile error testcases look identical to runtime errors`() {
        failedTestcases("algorithm-compile.jsonl").forAll {
            it.msg shouldBe "실패 (런타임 에러)"
            it.runTime shouldBe null
        }
    }

    // Failed algorithm gradings still end with a zero-score result and a normal finish (protocol doc §7).
    @Test
    fun `failed grading ends with zero score result and finish`() {
        val stream = FixtureLoader.messages("algorithm-timeout.jsonl")
        val result = stream.filterIsInstance<SubmitMessage.ResultLessonChallenge>().single()
        result.userScore shouldBe "0.0"
        result.passed shouldBe false
        stream.last().shouldBeInstanceOf<SubmitMessage.Finish>()
    }

    // run start carries the example testcases already structured (protocol doc §7).
    @Test
    fun `parses example testcases from run start`() {
        runErrorStream[0] shouldBe aStartMessage(
            action = "run",
            msg = null,
            exampleTestcases = listOf(ExampleTestcase("3, 2", "1"), ExampleTestcase("10, 5", "0")),
            hideResult = false,
            challengeableType = "algorithm",
            challengeableId = 14650L,
        )
    }

    @Test
    fun `parses html-escaped compiler output from run error`() {
        val error = runErrorStream[1].shouldBeInstanceOf<SubmitMessage.Error>()
        error.index shouldBe 0
        error.msg.shouldNotBeNull() shouldContain "error: &#39;;&#39; expected"
    }

    @Test
    fun `parses runtime stack trace from run error`() {
        val error = runErrorStream[2].shouldBeInstanceOf<SubmitMessage.Error>()
        error.index shouldBe 1
        error.msg.shouldNotBeNull() shouldContain "ArrayIndexOutOfBoundsException"
    }

    // Synthetic inline JSON — an unknown type cannot have a measured capture (test decision C).
    @Test
    fun `preserves unknown message type with raw json instead of dropping it`() {
        val raw =
            buildJsonObject {
                put("action", "submit")
                put("type", "mystery")
                put("payload", 42)
            }
        SubmitMessage.ofReceived(raw) shouldBe SubmitMessage.Unknown("mystery", raw)
    }

    @Test
    fun `treats message without type as Unknown`() {
        val raw = buildJsonObject { put("action", "submit") }
        SubmitMessage.ofReceived(raw) shouldBe SubmitMessage.Unknown(null, raw)
    }

    private fun failedTestcases(fixture: String): List<SubmitMessage.Testcase> = FixtureLoader.messages(fixture)
        .filterIsInstance<SubmitMessage.Testcase>()
        .also { it.shouldNotBeEmpty() }
}
