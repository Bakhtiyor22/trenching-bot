package com.trenching.bot.replay.domain

import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.TokenAccountFirstBuy

/**
 * Whether a wallet + token capture may plan its market window. Only an actual first buy that is proven and is the
 * token-account first buy qualifies; a later recognised buy is never substituted for it.
 */
object TargetFirstBuyGate {
    fun assess(evidence: TargetTokenEvidence): TargetFirstBuyAssessment =
        when (val tokenAccount = evidence.tokenAccountFirstBuy) {
            TokenAccountFirstBuy.NoBuy -> TargetFirstBuyAssessment.Refused(TargetBuyRefusal.NO_TARGET_BUY)
            is TokenAccountFirstBuy.NotEstablished -> TargetFirstBuyAssessment.Refused(TargetBuyRefusal.FIRST_BUY_UNPROVEN)
            is TokenAccountFirstBuy.Found -> actual(tokenAccount, evidence.actualFirstBuy)
        }

    private fun actual(tokenAccount: TokenAccountFirstBuy.Found, actual: ActualFirstBuy): TargetFirstBuyAssessment = when (actual) {
        is ActualFirstBuy.Proven ->
            if (actual.signature == tokenAccount.signature) TargetFirstBuyAssessment.Proven(actual)
            else TargetFirstBuyAssessment.Refused(TargetBuyRefusal.FIRST_BUY_UNPROVEN)
        is ActualFirstBuy.Unproven, ActualFirstBuy.NotVerified -> TargetFirstBuyAssessment.Refused(TargetBuyRefusal.FIRST_BUY_UNPROVEN)
        is ActualFirstBuy.OutsideTokenAccount -> TargetFirstBuyAssessment.Refused(TargetBuyRefusal.FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT)
    }
}

sealed interface TargetFirstBuyAssessment {
    data class Proven(val firstBuy: ActualFirstBuy.Proven) : TargetFirstBuyAssessment

    data class Refused(val reason: TargetBuyRefusal) : TargetFirstBuyAssessment
}

/** Why a wallet + token capture plans no market window; the name is the wallet stage's reason. */
enum class TargetBuyRefusal { NO_TARGET_BUY, FIRST_BUY_UNPROVEN, FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT }
