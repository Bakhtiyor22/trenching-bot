package com.trenching.bot.portfolio.domain

import com.trenching.bot.papertrading.domain.RejectionReason

sealed interface CloseOutcome {
    data class Closed(val position: Position) : CloseOutcome
    data class Unavailable(val reason: RejectionReason) : CloseOutcome
    data class AlreadyClosed(val position: Position) : CloseOutcome
}
