package com.trenching.bot.walletactivity.infrastructure.capture

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.AccumulatorScan
import com.trenching.bot.walletactivity.CreationSearch
import com.trenching.bot.walletactivity.VerifiedFirstBuy
import com.trenching.bot.walletactivity.WalletTokenVerification

/** The verification section a token-account capture writes when step 1 resolves, before any step 2 request. */
internal object VerificationStart {
    /** [firstBuy] is B, or null when step 1 found no token-account first buy and nothing is to be verified. */
    fun of(wallet: WalletAddress, firstBuy: VerifiedFirstBuy?): WalletTokenVerification = WalletTokenVerification(
        firstBuy = firstBuy,
        creation = CreationSearch(cursor = null, pages = 0, entries = 0, oldestSlot = null, historyEnd = false,
            candidates = emptyList(), signature = null, slot = null, blockTime = null),
        pumpAccumulator = scan(WalletHistoryListing.pumpAccumulator(wallet)),
        pumpSwapAccumulator = scan(WalletHistoryListing.pumpSwapAccumulator(wallet)),
        records = emptyList(),
        fetches = 0,
        result = null,
    )

    private fun scan(address: String) = AccumulatorScan(
        address = address, cursor = null, pages = 0, entries = 0, skippedFailed = 0, oldestSlot = null, listedFirstBuy = false, complete = false,
    )
}
