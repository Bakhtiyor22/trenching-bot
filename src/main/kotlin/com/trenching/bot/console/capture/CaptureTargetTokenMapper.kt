package com.trenching.bot.console.capture

import com.trenching.bot.console.api.dto.*
import com.trenching.bot.console.mapper.CaptureResponseMapper
import com.trenching.bot.replay.domain.ActualFirstBuyClaim
import com.trenching.bot.walletactivity.ActualFirstBuy
import com.trenching.bot.walletactivity.TargetTokenEvidence
import com.trenching.bot.walletactivity.TokenAccountFirstBuy
import com.trenching.bot.walletactivity.VerificationBoundHit
import com.trenching.bot.walletactivity.VerificationListing

class CaptureTargetTokenMapper(private val mapper: CaptureResponseMapper) {
    fun map(value: TargetTokenEvidence): CaptureTargetTokenResponse =
        mapper.targetToken(value.mint.value, value.tokenProgram, value.tokenAccount, value.pumpAccumulator, value.pumpSwapAccumulator, tokenAccount(value.tokenAccountFirstBuy), actual(value.actualFirstBuy))

    private fun tokenAccount(value: TokenAccountFirstBuy): CaptureTokenAccountFirstBuyResponse = when (value) {
        is TokenAccountFirstBuy.Found -> mapper.tokenAccountFirstBuy("FOUND", value.signature.value, value.slot.value, null, emptyList())
        TokenAccountFirstBuy.NoBuy -> mapper.tokenAccountFirstBuy("NO_BUY", null, null, null, emptyList())
        is TokenAccountFirstBuy.NotEstablished -> mapper.tokenAccountFirstBuy("NOT_ESTABLISHED", null, null, value.reason.name, value.blocking.map { it.value })
    }

    private fun actual(value: ActualFirstBuy): CaptureActualFirstBuyResponse = when (value) {
        is ActualFirstBuy.Proven -> mapper.actualFirstBuy("PROVEN", value.signature.value, value.slot.value, value.creationSignature.value, value.creationSlot.value, value.creationTime, null, emptyList(), ActualFirstBuyClaim.of(value), null)
        is ActualFirstBuy.Unproven -> mapper.actualFirstBuy("UNPROVEN", null, null, null, null, null, value.reason.name, value.blocking.map { it.value }, null, value.bound?.let(::bound))
        is ActualFirstBuy.OutsideTokenAccount -> mapper.actualFirstBuy("OUTSIDE_TOKEN_ACCOUNT", value.signature.value, value.slot.value, null, null, null, null, emptyList(), null, null)
        ActualFirstBuy.NotVerified -> mapper.actualFirstBuy("NOT_VERIFIED", null, null, null, null, null, null, emptyList(), null, null)
    }

    private fun bound(value: VerificationBoundHit): CaptureVerificationBoundResponse =
        mapper.verificationBound(value.limit.name, named(value.pages), named(value.entries), value.fetches, named(value.oldestSlot))

    /** Listing order, not provider order, so the JSON is the same for the same evidence. */
    private fun <V> named(value: Map<VerificationListing, V>): Map<String, V> = value.toSortedMap().mapKeys { it.key.name }
}
