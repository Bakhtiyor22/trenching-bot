package com.trenching.bot.walletactivity.domain

import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature

/** One record listed for the wallet's token account, reduced to what decides the token-account first buy. */
internal data class TokenAccountRecord(val signature: TransactionSignature, val slot: Slot, val kind: TokenAccountRecordKind)

internal sealed interface TokenAccountRecordKind {
    /** A decoded buy of the target mint; [creditsTokenAccount] is whether it raised the token account's balance of the mint. */
    data class TargetBuy(val instructionIndex: Int, val creditsTokenAccount: Boolean) : TokenAccountRecordKind

    /** Signed by the wallet but not decoded: an unsupported route, an ambiguous buy, or a buy of another mint. */
    data class Undecoded(val walletBalance: TargetBalanceChange) : TokenAccountRecordKind

    /** Listed, but without an artifact to inspect. */
    data object Unknown : TokenAccountRecordKind

    /** Never an acquisition by the wallet: not signed by it, without it, or failed. */
    data object Irrelevant : TokenAccountRecordKind
}

/** How a transaction changed the wallet's total balance of the target mint. */
internal enum class TargetBalanceChange { INCREASED, NOT_INCREASED, UNMEASURABLE }
