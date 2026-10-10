package com.trenching.bot.portfolio.domain

import com.trenching.bot.papertrading.domain.RejectionReason

sealed interface CloseOutcome {
    data class Closed(val position: Position) : CloseOutcome

    /** The position stays open for [reason]; [position] carries its exit trigger when one was seen. */
    data class Unavailable(val position: Position, val reason: RejectionReason) : CloseOutcome
    data class AlreadyClosed(val position: Position) : CloseOutcome
}
