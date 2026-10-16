package com.trenching.bot.console.capture

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.WalletAction
import java.math.BigInteger

/** The notes that help pick a token from a wallet's activity page; pure, so the same page and captures give the same notes. */
object WalletActivityAnnotations {
    const val SIGNAL_BASIS = "CURVE_PAYMENT_EXCLUDING_FEES"

    /** Strictly more than 1.9 SOL; exactly 1.9 SOL is not over it. */
    val SIGNAL_THRESHOLD_LAMPORTS: BigInteger = BigInteger.valueOf(1_900_000_000)

    /** [actions] are newest first, so an earlier action of the same mint is one further down the page. */
    fun annotate(actions: List<WalletAction>, capturedIn: Map<String, List<CapturedInView>>): List<WalletActionView> =
        actions.mapIndexed { index, action ->
            WalletActionView(
                action = action,
                earlierActionInPage = actions.subList(index + 1, actions.size).any { it.mint == action.mint },
                overSignalThreshold = action.solLamports > SIGNAL_THRESHOLD_LAMPORTS,
                signalBasis = SIGNAL_BASIS,
                capturedIn = capturedIn[action.mint.value].orEmpty(),
            )
        }

    /**
     * Mint to the captures of [wallet] that cover it, by their wallet + token mint or an inspected market window, oldest first.
     * [displayNames] maps session ids to their display names.
     */
    fun capturedIn(records: List<CaptureSessionRecord>, wallet: WalletAddress, displayNames: Map<String, String>): Map<String, List<CapturedInView>> =
        records.filter { it.wallet?.wallet == wallet.value }
            .sortedWith(compareBy<CaptureSessionRecord> { it.createdAt }.thenBy { it.id })
            .flatMap { record -> mints(record).map { mint -> mint to CapturedInView(record.id, displayNames[record.id] ?: record.name) } }
            .groupBy({ it.first }, { it.second })

    private fun mints(record: CaptureSessionRecord): Set<String> =
        (listOfNotNull(record.wallet?.mint) + record.inspection?.marketWindows.orEmpty().map { it.mint }).toSet()
}
