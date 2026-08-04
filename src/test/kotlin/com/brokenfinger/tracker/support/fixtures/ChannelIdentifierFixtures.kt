package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.protocol.ChallengeableId
import com.brokenfinger.tracker.protocol.ChallengeableType
import com.brokenfinger.tracker.protocol.ChannelIdentifier
import com.brokenfinger.tracker.protocol.LessonId

// Object mothers (dev rules §6.4). Defaults mirror the measured algorithm-pass.jsonl
// and sql-pass.jsonl captures (protocol doc §3, §15).

fun anAlgorithmIdentifier(lessonId: Long = 120804, challengeableId: Long = 14643, language: String = "java") =
    ChannelIdentifier.of(ChallengeableType.ALGORITHM, LessonId(lessonId), ChallengeableId(challengeableId), language)

fun aSqlIdentifier(lessonId: Long = 131528, challengeableId: Long = 2778, language: String = "mysql") =
    ChannelIdentifier.of(ChallengeableType.DATABASE, LessonId(lessonId), ChallengeableId(challengeableId), language)
