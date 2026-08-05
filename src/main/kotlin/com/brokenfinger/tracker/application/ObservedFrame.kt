package com.brokenfinger.tracker.application

import com.brokenfinger.tracker.domain.GradingFrameFacts

/**
 * One frame the capture path was handed: the wire text exactly as it arrived, and the facts
 * `protocol/parse` read out of it.
 *
 * Both halves are needed and neither replaces the other. [rawText] is what stage 1 appends
 * before anything is interpreted — a grading Programmers has already broadcast can never be
 * fetched again (protocol doc §11) — and it is passed through untouched, never re-serialized
 * (dev rules §2.4). It is opaque here: nothing in `application` reads inside it.
 *
 * [facts] is null for a frame that said nothing about a grading — a subscription
 * confirmation, an envelope of an unknown shape, text no parser could read. Those are
 * appended all the same and contribute nothing to the verdict.
 */
data class ObservedFrame(val rawText: String, val facts: GradingFrameFacts? = null)
