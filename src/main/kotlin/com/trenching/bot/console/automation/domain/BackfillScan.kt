package com.trenching.bot.console.automation.domain

import com.trenching.bot.walletactivity.WalletAction
import com.trenching.bot.walletactivity.WalletActivityUnavailable
import java.time.Instant

/**
 * What a backfill's next scan tick needs: the range, where to continue ([resumeBefore]) or, for the first tick, [seed], the
 * signature of the wallet's earliest stored buy at or after the range end to enter the listing after, and the [budget] so far.
 */
data class BackfillScanTarget(
    val id: String,
    val wallet: String,
    val range: TimeRange,
    val resumeBefore: String?,
    val seed: String?,
    val budget: BackfillBudget,
) {
    /** The `before` signature of the tick's first page: the stored cursor, else the seed, else the newest transaction. */
    val startBefore: String? get() = resumeBefore ?: seed
}

/** How a backfill's scan tick ended. */
sealed interface BackfillScanEnding {
    /** The page limit ended the tick; the next one continues from [resumeBefore]. */
    data class Paused(val resumeBefore: String) : BackfillScanEnding

    /** The scan read down to the range start. */
    data object ReachedStart : BackfillScanEnding

    /** The next page might pass the request budget, so the scan stopped before it, at [resumeBefore]. */
    data class OutOfBudget(val resumeBefore: String) : BackfillScanEnding
}

/**
 * One scan tick: the [actions] inside [range] it read (oldest first), the provider [requests] it spent and how it [ending].
 * [scannedTo] is the oldest instant every page it read covers, null when none gave a block time; [untimed] counts the actions it
 * read that had no block time and so could not be placed in the range.
 */
data class BackfillBatch(
    val backfillId: String,
    val range: TimeRange,
    val actions: List<WalletAction>,
    val requests: Long,
    val ending: BackfillScanEnding,
    val scannedTo: Instant?,
    val untimed: Int,
)

/** What storing a scan tick did: the items it created, the buys the token list left out, and the backfill's state after it. */
data class BackfillRecord(val itemsCreated: Int, val skippedByFilter: Int, val state: BackfillState)

sealed interface BackfillScanOutcome {
    /** No backfill is waiting to be scanned. */
    data object NothingToScan : BackfillScanOutcome

    /** The rolling request total is within the reserve of the cap, so live capture keeps its headroom (plan Rule 37). */
    data object ReserveReached : BackfillScanOutcome

    /** The provider could not be read; nothing was stored and the next tick reads the same pages. */
    data class Unavailable(val reason: WalletActivityUnavailable) : BackfillScanOutcome

    data class Recorded(val record: BackfillRecord) : BackfillScanOutcome
}
