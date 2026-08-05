package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.protocol.message.ExampleTestcase
import com.brokenfinger.tracker.protocol.message.Score
import com.brokenfinger.tracker.protocol.message.SubmitMessage

// Object mothers (dev rules §6.4). Defaults mirror the algorithm-pass.jsonl measured
// capture; tests override only the fields that differ. Korean literals are measured
// protocol values kept verbatim (protocol doc §7).

fun aStartMessage(
    action: String? = "submit",
    msg: String? = "채점을 시작합니다.",
    testcaseIds: List<Long>? = null,
    exampleTestcases: List<ExampleTestcase>? = null,
    hideResult: Boolean? = null,
    challengeableType: String? = null,
    challengeableId: Long? = null,
) = SubmitMessage.Start(action, msg, testcaseIds, exampleTestcases, hideResult, challengeableType, challengeableId)

fun aTestGroupMessage(
    action: String? = "submit",
    category: String? = "correctness",
    testcaseIds: List<Long>? = listOf(154893L, 154894L),
    msg: String? = "정확성  테스트",
) = SubmitMessage.TestGroup(action, category, testcaseIds, msg)

fun aTestcaseMessage(
    action: String? = "submit",
    testcaseId: Long? = 154893L,
    testcasesCount: Int? = 16,
    passed: Boolean? = true,
    msg: String? = "통과 (0.01ms, 85.2MB)",
    runTime: String? = "0.01",
    memorySize: Long? = 89338675L,
    challengeableType: String? = null,
    challengeableId: Long? = null,
) = SubmitMessage.Testcase(
    action, testcaseId, testcasesCount, passed, msg, runTime, memorySize, challengeableType, challengeableId,
)

fun aResultMessage(
    action: String? = "submit",
    passed: Boolean? = true,
    scores: List<Score>? = listOf(aScore()),
    userScore: String? = "100.0",
    perfectScore: String? = "100.0",
    challengeableId: Long? = 14643L,
    challengeableType: String? = null,
    language: String? = "java",
    isNewRating: Boolean? = true,
    oldUserRating: Int? = 1000,
    newUserRating: Int? = 1001,
    finishModalLink: String? = "/learn/courses/30/lessons/0/solution_groups?language=java",
    finishModalBtnText: String? = null,
    surveyUrl: String? = "/custom_form_groups/0",
) = SubmitMessage.ResultLessonChallenge(
    action, passed, scores, userScore, perfectScore, challengeableId, challengeableType,
    language, isNewRating, oldUserRating, newUserRating, finishModalLink, finishModalBtnText, surveyUrl,
)

fun aFinishMessage(
    action: String? = "submit",
    testcaseId: Long? = null,
    passed: Boolean? = null,
    msg: String? = null,
    returnedRows: String? = null,
    challengeableType: String? = null,
    challengeableId: Long? = null,
) = SubmitMessage.Finish(action, testcaseId, passed, msg, returnedRows, challengeableType, challengeableId)

fun aScore(name: String? = "정확성", score: String? = "100.0") = Score(name, score)
