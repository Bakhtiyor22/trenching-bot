package com.trenching.bot.console.domain

/**
 * What happened to [count] of a run's tokens, for one reason: [code] is the name of a close reason ([RunOutcomeStage.SOLD]), or of
 * the rejection reason that left a position open ([RunOutcomeStage.HELD]) or stopped the buy ([RunOutcomeStage.NOT_BOUGHT]).
 */
data class RunOutcome(val code: String, val stage: RunOutcomeStage, val count: Int)

/** Declared in the order a run's outcomes are listed in. */
enum class RunOutcomeStage { SOLD, HELD, NOT_BOUGHT }
