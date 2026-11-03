package com.trenching.bot.console.automation.domain

import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.Position

/** The cycle of an item's buy in one sweep run, as the run's stores hold it. */
sealed interface SweepCycle {
    /** No order filled: [reason] is the watch or rejection reason. */
    data class Skipped(val reason: RejectionReason) : SweepCycle

    /** The order filled; [exitUnavailable] says why [position] is still open, if it is. */
    data class Entered(val position: Position, val exitUnavailable: RejectionReason?) : SweepCycle
}
