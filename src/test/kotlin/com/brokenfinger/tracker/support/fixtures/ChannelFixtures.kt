package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.domain.ChallengeableId
import com.brokenfinger.tracker.domain.ChannelKey
import com.brokenfinger.tracker.domain.LessonId
import com.brokenfinger.tracker.domain.ProblemKind
import com.brokenfinger.tracker.protocol.ChannelIdentifier

// Object mothers (dev rules §6.4). Defaults mirror the measured algorithm-pass.jsonl
// and sql-pass.jsonl captures (protocol doc §3, §15).
//
// The identifier builders delegate to the key builders, so the wire form a protocol test
// asserts on and the identity an application test holds can never drift apart.

fun anAlgorithmChannel(lessonId: Long = 120804, challengeableId: Long = 14643, language: String = "java") =
    ChannelKey.of(LessonId(lessonId), ChallengeableId(challengeableId), ProblemKind.ALGORITHM, language)

fun aSqlChannel(lessonId: Long = 131528, challengeableId: Long = 2778, language: String = "mysql") =
    ChannelKey.of(LessonId(lessonId), ChallengeableId(challengeableId), ProblemKind.DATABASE, language)

fun anAlgorithmIdentifier(lessonId: Long = 120804, challengeableId: Long = 14643, language: String = "java") =
    ChannelIdentifier.from(anAlgorithmChannel(lessonId, challengeableId, language))

fun aSqlIdentifier(lessonId: Long = 131528, challengeableId: Long = 2778, language: String = "mysql") =
    ChannelIdentifier.from(aSqlChannel(lessonId, challengeableId, language))
