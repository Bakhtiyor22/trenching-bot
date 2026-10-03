package com.trenching.bot.portfolio.domain

import com.trenching.bot.papertrading.domain.RejectionReason

data class StoredPosition(val position: Position, val exitUnavailable: RejectionReason?)
