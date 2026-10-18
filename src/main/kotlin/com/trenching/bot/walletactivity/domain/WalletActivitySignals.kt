package com.trenching.bot.walletactivity.domain

import com.trenching.bot.walletactivity.WalletAction
import java.math.BigInteger

/** Readings that help pick a token from a page of [WalletAction]s. None of them proves a first buy or decides a run. */
object WalletActivitySignals {
    /** The decu-dip signal size, read here from the curve payment alone (fees excluded). */
    val SIGNAL_THRESHOLD_LAMPORTS: BigInteger = BigInteger.valueOf(1_900_000_000)

    const val SIGNAL_BASIS = "CURVE_PAYMENT_EXCLUDING_FEES"

    /** Newest slot first; within a slot by signature, then instruction, with a top-level event before its inner events. */
    val PAGE_ORDER: Comparator<WalletAction> = compareByDescending<WalletAction> { it.slot }
        .thenBy { it.signature.value }
        .thenBy { it.instructionIndex }
        .thenBy(nullsFirst()) { it.innerIndex }

    fun overSignalThreshold(action: WalletAction): Boolean = action.solLamports > SIGNAL_THRESHOLD_LAMPORTS

    /**
     * Whether [page] holds an older action of the same mint. Older means a lower slot, or an earlier event of the same
     * transaction; two transactions of one slot have no known order, so neither counts as older.
     */
    fun hasEarlierActionInPage(action: WalletAction, page: List<WalletAction>): Boolean =
        page.any { it.mint == action.mint && isOlder(it, action) }

    private fun isOlder(candidate: WalletAction, action: WalletAction): Boolean = when {
        candidate.slot != action.slot -> candidate.slot < action.slot
        candidate.signature != action.signature -> false
        else -> compareValuesBy(candidate, action, { it.instructionIndex }, { it.innerIndex ?: TOP_LEVEL }) < 0
    }

    private const val TOP_LEVEL = -1
}
