package com.trenching.bot.console.domain

import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.strategy.domain.TradeIntent
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.WalletTransaction

data class RunEvent(val eventId: String, val transaction: WalletTransaction, val outcome: NormalizationOutcome)

data class RunOrder(val intent: TradeIntent, val outcome: FillOutcome)

data class RunPosition(val position: Position, val exitUnavailable: RejectionReason?)
