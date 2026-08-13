package com.brokenfinger.tracker.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Family of problem being graded. The two families differ in more than presentation:
 * their response shape and their termination frame both diverge (protocol doc §6).
 */
@Serializable
enum class ProblemKind {
    // The wire spellings, which are also what a record stores and what an Obsidian query matches
    // on: `database`, never `sql`. The bundle carries a `Challenge::SqlChannel` that no coding-test
    // problem uses (protocol §2).
    @SerialName("algorithm")
    ALGORITHM,

    @SerialName("database")
    DATABASE,
}
