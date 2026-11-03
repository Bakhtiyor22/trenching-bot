package com.trenching.bot.console.automation.domain

import java.time.Duration

/** How much one tick of the sweep and of scoring may do, so a tick stays short; the rest waits for the next tick. */
object AutomationLimits {
    /** Captured items swept per tick; each replays every entry of the plan. */
    const val SWEEP_ITEMS_PER_TICK = 2

    /** How long an item waits to be swept again after its first failed sweep; each further failure doubles it. */
    val SWEEP_RETRY_DELAY: Duration = Duration.ofMinutes(5)

    /** Closed sessions evaluated per tick, oldest first buy first. */
    const val EVALUATIONS_PER_TICK = 20

    /** Items read per session, for its detail or an evaluation window; a night holds far fewer buys. */
    const val MAX_ITEMS_PER_SESSION = 1000
}
