package com.trenching.bot.walletactivity

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature

/**
 * What a [WalletHistoryScope.WALLET_TOKEN_ACCOUNT] capture shows about the wallet's first buy of [mint]. [tokenAccount] is the
 * wallet's associated token account under [tokenProgram]; the accumulators are the wallet's Pump and PumpSwap user volume
 * accumulators. [tokenAccountFirstBuy] and [actualFirstBuy] are separate results and never stand in for each other.
 */
data class TargetTokenEvidence(
    val mint: MintAddress,
    val tokenProgram: String,
    val tokenAccount: String,
    val pumpAccumulator: String,
    val pumpSwapAccumulator: String,
    val tokenAccountFirstBuy: TokenAccountFirstBuy,
    val actualFirstBuy: ActualFirstBuy,
)

/** The earliest decoded buy of the target mint among the transactions listed for the wallet's token account. */
sealed interface TokenAccountFirstBuy {
    data class Found(val signature: TransactionSignature, val slot: Slot, val instructionIndex: Int) : TokenAccountFirstBuy

    /** The listing was exhausted and holds no decoded buy of the mint: a complete, valid answer. */
    data object NoBuy : TokenAccountFirstBuy

    /** [blocking] lists the signatures that justify [reason], in slot then signature order. */
    data class NotEstablished(val reason: TokenAccountGap, val blocking: List<TransactionSignature>) : TokenAccountFirstBuy
}

enum class TokenAccountGap {
    /** Discovery stopped at its record limit or scan bound, so older transactions may exist. */
    HISTORY_NOT_EXHAUSTED,

    /** A wallet-signed, undecoded record at or before the buy's slot raised, or may have raised, the wallet's balance of the mint. */
    EARLIER_UNDECODED_ACQUISITION,

    /** A record at or before the buy's slot has no artifact to inspect. */
    EARLIER_UNKNOWN_RECORD,

    /** The earliest decoded buy did not raise the token account's balance of the mint. */
    NON_ATA_CREDIT,

    /** Another decoded buy of the mint shares the earliest buy's slot, and within-slot order is not relied on. */
    SAME_SLOT_BUY,
}
