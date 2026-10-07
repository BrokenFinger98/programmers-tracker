package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.application.GradingCodes
import com.brokenfinger.tracker.domain.calc.KeptCode

// Object mother for the kept-code port (dev rules §6.4). No record and no file can break the invariants
// behind repair steps, so the only way to show how such a fault is reported is a collaborator that
// throws the exception one of them would. The port is the seam because the query reads code for every
// problem it assembles, and a real adapter never throws (it answers "not kept").

/**
 * A code store that throws [failure] whenever it is asked for code, and answers nothing otherwise.
 * [failure] can be set and cleared, so one Spring context can be healthy for every test but the one
 * that breaks it.
 */
class FailingGradingCodes(var failure: RuntimeException? = null) : GradingCodes {
    override fun runs(lessonId: Long, title: String?): Map<String, KeptCode> {
        failure?.let { throw it }
        return emptyMap()
    }

    override fun submitted(codePath: String): String? {
        failure?.let { throw it }
        return null
    }
}

/** Fails the way an invariant of ours does: an IllegalArgumentException no argument could have caused. */
fun aBrokenGradingCodes(message: String = "a label is one problem's"): FailingGradingCodes =
    FailingGradingCodes(IllegalArgumentException(message))
