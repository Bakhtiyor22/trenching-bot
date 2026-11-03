package com.trenching.bot.console.automation.domain

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate

/**
 * A backfill as stored (plan Key Decisions 6-10). [rangeStart] and [rangeEnd] are the range after clipping to live coverage.
 * The counts cover what the scan has read so far: [actionsScanned] inside the range, [buysFound] of them, [buysPassing] the
 * filter (size and token list), [itemsCreated] of those that became items, [skippedByFilter] the buys the token list left out and
 * [skippedNoBlockTime] the actions the listing gave no block time, which cannot be placed in the range. [summary] and
 * [estimatedRequests] are a dry run's result (plan Rule 42); [resumeBefore] is where a running scan continues and [scannedTo] how
 * far back it has read: the range start once it finished. A [filtered] backfill never counts as coverage.
 */
data class BackfillView(
    val id: String,
    val requestId: String,
    val wallet: String,
    val fromDay: LocalDate,
    val toDay: LocalDate,
    val rangeStart: Instant,
    val rangeEnd: Instant,
    val dryRun: Boolean,
    val minSignalLamports: BigInteger?,
    val mints: List<String>,
    val requestBudget: Long,
    val discoveryRequests: Long,
    val state: BackfillState,
    val resumeBefore: String?,
    val actionsScanned: Int,
    val buysFound: Int,
    val buysPassing: Int,
    val itemsCreated: Int,
    val skippedByFilter: Int,
    val skippedNoBlockTime: Int,
    val scannedTo: Instant?,
    val filtered: Boolean,
    val summary: DryRunSummary?,
    val estimatedRequests: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant?,
)

/**
 * A backfill with its items' provider requests ([itemRequests]) and item counts for every state (zeros included). Its spend
 * against the request budget is [requestsSpent] (plan Rule 39).
 */
data class AutomationBackfillRow(
    val backfill: BackfillView,
    val itemRequests: Long,
    val itemCounts: Map<AutomationItemState, Long>,
) {
    val requestsSpent: Long get() = backfill.discoveryRequests + itemRequests
}
