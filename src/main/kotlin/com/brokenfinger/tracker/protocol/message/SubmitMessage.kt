package com.brokenfinger.tracker.protocol.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/**
 * Inner message broadcast on a challenge channel — the `message` payload of a
 * [ActionCableFrame.Broadcast].
 *
 * Every field is nullable: measured null counterexamples exist for `run_time`,
 * `memory_size`, and `msg` (protocol doc §6–§7). Algorithm messages use camelCase
 * (`testcaseId`) while SQL uses snake_case (`testcase_id`) — factories accept both
 * spellings (§6). Unrecognized types are preserved as [Unknown] with a warning;
 * silently dropping them is forbidden (dev rules §2.3).
 */
sealed interface SubmitMessage {
    data class Start(
        val action: String?,
        val msg: String?,
        val testcaseIds: List<Long>?,
        val exampleTestcases: List<ExampleTestcase>?,
        val hideResult: Boolean?,
        val challengeableType: String?,
        val challengeableId: Long?,
    ) : SubmitMessage

    data class TestGroup(val action: String?, val category: String?, val testcaseIds: List<Long>?, val msg: String?) :
        SubmitMessage

    data class Testcase(
        val action: String?,
        val testcaseId: Long?,
        /** run frames identify a case by 0-based `index` instead of `testcaseId` (§7). */
        val index: Int?,
        val testcasesCount: Int?,
        val passed: Boolean?,
        val msg: String?,
        val runTime: String?,
        val memorySize: Long?,
        val challengeableType: String?,
        val challengeableId: Long?,
    ) : SubmitMessage

    data class ResultLessonChallenge(
        val action: String?,
        val passed: Boolean?,
        val scores: List<Score>?,
        val userScore: String?,
        val perfectScore: String?,
        val challengeableId: Long?,
        val challengeableType: String?,
        val language: String?,
        val isNewRating: Boolean?,
        val oldUserRating: Int?,
        val newUserRating: Int?,
        val finishModalLink: String?,
        val finishModalBtnText: String?,
        val surveyUrl: String?,
    ) : SubmitMessage

    /**
     * Terminal frame for algorithm submits; SQL `run` reuses it to carry the result
     * (`testcase_id`, `returned_rows`) while SQL submit never sends it (protocol doc §6).
     */
    data class Finish(
        val action: String?,
        val testcaseId: Long?,
        val passed: Boolean?,
        val msg: String?,
        val returnedRows: String?,
        val challengeableType: String?,
        val challengeableId: Long?,
    ) : SubmitMessage

    /**
     * Terminal frame of an algorithm `run` (protocol doc §8 catalog; frame-level capture in
     * `fixtures/algorithm-run-pass.jsonl` from our own live verification, issues #6 and #10).
     * Counts are per example testcase, not per grading testcase.
     */
    data class Result(
        val action: String?,
        val passed: Boolean?,
        val passedCount: Int?,
        val totalCount: Int?,
        val hideResult: Boolean?,
        val challengeableType: String?,
        val challengeableId: Long?,
    ) : SubmitMessage

    /** run-path error with HTML-escaped compiler output or stack trace (protocol doc §7). */
    data class Error(val action: String?, val index: Int?, val msg: String?) : SubmitMessage

    data class Unknown(val type: String?, val raw: JsonObject) : SubmitMessage {
        /**
         * The `action` the frame declared, if any. Every other message reads it into a field;
         * this one cannot, because it is the case for frames whose shape we do not know — and
         * a frame can declare an action while carrying no `type` at all. A `reset` broadcast
         * does exactly that (measured 2026-08-12, protocol §8), and telling it apart from a
         * grading needs the string.
         */
        fun action(): String? = raw.stringField("action")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SubmitMessage::class.java)

        fun ofReceived(message: JsonObject): SubmitMessage = when (val type = message.stringField("type")) {
            "start" -> parseStart(message)
            "test_group" -> parseTestGroup(message)
            "testcase" -> parseTestcase(message)
            "result_lesson_challenge" -> parseResult(message)
            "result" -> parseRunResult(message)
            "finish" -> parseFinish(message)
            "error" -> parseError(message)
            else -> unknown(type, message)
        }

        private fun parseStart(message: JsonObject) = Start(
            action = message.stringField("action"),
            msg = message.stringField("msg"),
            testcaseIds = message.longListField("testcaseIds", "testcase_ids"),
            exampleTestcases = message.exampleTestcaseList("testcases"),
            hideResult = message.booleanField("hideResult"),
            challengeableType = message.stringField("challengeable_type", "challengeableType"),
            challengeableId = message.longField("challengeable_id", "challengeableId"),
        )

        private fun parseTestGroup(message: JsonObject) = TestGroup(
            action = message.stringField("action"),
            category = message.stringField("category"),
            testcaseIds = message.longListField("testcaseIds", "testcase_ids"),
            msg = message.stringField("msg"),
        )

        private fun parseTestcase(message: JsonObject) = Testcase(
            action = message.stringField("action"),
            testcaseId = message.longField("testcaseId", "testcase_id"),
            index = message.intField("index"),
            testcasesCount = message.intField("testcasesCount"),
            passed = message.booleanField("passed"),
            msg = message.stringField("msg"),
            runTime = message.stringField("run_time"),
            memorySize = message.longField("memory_size"),
            challengeableType = message.stringField("challengeable_type"),
            challengeableId = message.longField("challengeable_id"),
        )

        private fun parseResult(message: JsonObject) = ResultLessonChallenge(
            action = message.stringField("action"),
            passed = message.booleanField("passed"),
            scores = message.scoreList("scores"),
            userScore = message.stringField("userScore"),
            perfectScore = message.stringField("perfectScore"),
            challengeableId = message.longField("challengeableId", "challengeable_id"),
            challengeableType = message.stringField("challengeable_type", "challengeableType"),
            language = message.stringField("language"),
            isNewRating = message.booleanField("isNewRating"),
            oldUserRating = message.intField("oldUserRating"),
            newUserRating = message.intField("newUserRating"),
            finishModalLink = message.stringField("finishModalLink"),
            finishModalBtnText = message.stringField("finishModalBtnText"),
            surveyUrl = message.stringField("surveyUrl"),
        )

        private fun parseRunResult(message: JsonObject) = Result(
            action = message.stringField("action"),
            passed = message.booleanField("passed"),
            passedCount = message.intField("passedCount"),
            totalCount = message.intField("totalCount"),
            hideResult = message.booleanField("hideResult"),
            challengeableType = message.stringField("challengeable_type"),
            challengeableId = message.longField("challengeable_id"),
        )

        private fun parseFinish(message: JsonObject) = Finish(
            action = message.stringField("action"),
            testcaseId = message.longField("testcaseId", "testcase_id"),
            passed = message.booleanField("passed"),
            msg = message.stringField("msg"),
            returnedRows = message.stringField("returned_rows"),
            challengeableType = message.stringField("challengeable_type"),
            challengeableId = message.longField("challengeable_id"),
        )

        private fun parseError(message: JsonObject) = Error(
            action = message.stringField("action"),
            index = message.intField("index"),
            msg = message.stringField("msg"),
        )

        private fun unknown(type: String?, message: JsonObject): Unknown {
            logger.warn("Unknown submit message type '{}' preserved as-is", type)
            return Unknown(type, message)
        }
    }
}

data class Score(val name: String?, val score: String?)

data class ExampleTestcase(val input: String?, val output: String?)

private fun JsonObject.primitiveField(vararg names: String): JsonPrimitive? =
    names.firstNotNullOfOrNull { this[it] as? JsonPrimitive }

private fun JsonObject.stringField(vararg names: String): String? = primitiveField(*names)?.contentOrNull

private fun JsonObject.longField(vararg names: String): Long? = primitiveField(*names)?.longOrNull

private fun JsonObject.intField(vararg names: String): Int? = primitiveField(*names)?.intOrNull

private fun JsonObject.booleanField(vararg names: String): Boolean? = primitiveField(*names)?.booleanOrNull

private fun JsonObject.arrayField(vararg names: String): JsonArray? =
    names.firstNotNullOfOrNull { this[it] as? JsonArray }

private fun JsonObject.longListField(vararg names: String): List<Long>? =
    arrayField(*names)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }

private fun JsonObject.exampleTestcaseList(name: String): List<ExampleTestcase>? =
    arrayField(name)?.mapNotNull { it.asExampleTestcase() }

private fun JsonElement.asExampleTestcase(): ExampleTestcase? =
    (this as? JsonObject)?.let { ExampleTestcase(it.stringField("input"), it.stringField("output")) }

private fun JsonObject.scoreList(name: String): List<Score>? = arrayField(name)?.mapNotNull { element ->
    (element as? JsonObject)?.let { Score(it.stringField("name"), it.stringField("score")) }
}
