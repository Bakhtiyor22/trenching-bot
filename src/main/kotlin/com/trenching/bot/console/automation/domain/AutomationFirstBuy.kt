package com.trenching.bot.console.automation.domain

import com.trenching.bot.replay.domain.TargetBuyRefusal

/**
 * Plan Rule 18: a wallet + token verdict classified by one of the first-buy refusals (each is reported as the classification of
 * the same name) leaves the buy unproven as the wallet's first buy of the mint, so it is never captured further.
 */
object AutomationFirstBuy {
    private val REFUSALS: Set<String> = TargetBuyRefusal.entries.map { it.name }.toSet()

    fun unproven(classification: String?): Boolean = classification in REFUSALS
}
