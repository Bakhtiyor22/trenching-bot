package com.trenching.bot.console.capture

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.domain.CaptureReference
import com.trenching.bot.console.domain.CapturedTokens

/**
 * Reads, from the session files, which of a wallet's captures already hold a mint: the wallet + token capture's target,
 * or any market window the capture's inspection found. Captures of other wallets never count. While any session file
 * cannot be read, no mint is marked captured.
 */
class CaptureTokenIndex(private val store: CaptureFileStore) : CapturedTokens {
    override fun capturesOf(wallet: WalletAddress): Map<String, List<CaptureReference>> = synchronized(store) {
        CaptureEnrichment.readOr(fallback = emptyMap(), VIEW, wallet.value) {
            val records = store.sessionRecords()
            val names = CaptureNamePolicy.displayNames(records)
            records.filter { it.wallet?.wallet == wallet.value }
                .sortedWith(compareByDescending<CaptureSessionRecord> { it.createdAt }.thenBy { it.id })
                .flatMap { record -> mints(record).map { mint -> mint to CaptureReference(record.id, names.getValue(record.id)) } }
                .groupBy({ it.first }, { it.second })
        }
    }

    private fun mints(record: CaptureSessionRecord): Set<String> = buildSet {
        record.wallet?.mint?.let(::add)
        record.inspection?.marketWindows?.forEach { add(it.mint) }
    }

    private companion object {
        const val VIEW = "wallet_activity_captures"
    }
}
