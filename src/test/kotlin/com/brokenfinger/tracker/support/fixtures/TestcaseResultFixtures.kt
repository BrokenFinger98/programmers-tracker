package com.brokenfinger.tracker.support.fixtures

import com.brokenfinger.tracker.domain.TestcaseResult

// Object mother (dev rules §6.4). Defaults describe a passing algorithm testcase from the
// algorithm-pass.jsonl measured capture; tests override only what differs. Korean literals
// are measured protocol values kept verbatim (protocol doc §7).

fun aTestcaseResult(
    id: Long = 1,
    passed: Boolean? = true,
    msg: String? = "통과 (0.01ms, 85.2MB)",
    runTime: String? = "0.01",
    memorySize: Long? = 89338675L,
) = TestcaseResult(id, passed, msg, runTime, memorySize)
