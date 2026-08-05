package com.brokenfinger.tracker.domain

/**
 * Family of problem being graded. The two families differ in more than presentation:
 * their response shape and their termination frame both diverge (protocol doc §6).
 */
enum class ProblemKind {
    ALGORITHM,
    DATABASE,
}
