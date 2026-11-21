package com.trenching.bot.console.automation.domain

import com.trenching.bot.replay.domain.TargetBuyRefusal

/**
 * Plan Rule 18: a wallet + token verdict classified by one of the first-buy refusals (each is reported as the classification of
 * the same name) leaves the buy unproven as the wallet's first buy of the mint, so it is never captured further.
 */
object AutomationFirstBuy {
    private val REFUSALS: Set<String> = TargetBuyRefusal.entries.map { it.name }.toSet()

    private val clauses: Map<String, String> = mapOf(
        TargetBuyRefusal.NO_TARGET_BUY.name to "the wallet's token account shows no buy of this token",
        TargetBuyRefusal.FIRST_BUY_UNPROVEN.name to "the wallet's first buy of this token could not be proven",
        TargetBuyRefusal.FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT.name to "the wallet's first buy of this token is outside the token account's history",
    )

    private const val UNKNOWN = "the capture's verdict does not prove this is the wallet's first buy of the token"

    fun unproven(classification: String?): Boolean = classification in REFUSALS

    /** The lower-case clause, with no full stop, that says why a buy with [classification] is not captured further. */
    fun meaning(classification: String?): String = classification?.let(clauses::get) ?: UNKNOWN
}
