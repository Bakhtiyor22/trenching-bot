package com.trenching.bot.console.automation.domain

import java.time.Instant

/**
 * What the automation logged for its buys between [since] and [until]: the counts the hourly summary reports. [failedByCode]
 * counts each buy that failed for good under its failure code; [retriedAttempts] counts failed attempts another one followed.
 * [discoveryScans] counts the live discovery scans that ran; it is routine, so it is not part of [isEmpty].
 */
data class AutomationActivityCounts(
    val since: Instant,
    val until: Instant,
    val liveBuysFound: Int = 0,
    val backfillBuysFound: Int = 0,
    val captured: Int = 0,
    val skipped: Int = 0,
    val simulated: Int = 0,
    val retriedAttempts: Int = 0,
    val failedByCode: Map<String, Int> = emptyMap(),
    val abandoned: Int = 0,
    val retriedByPerson: Int = 0,
    val discoveryScans: Int = 0,
) {
    val failed: Int get() = failedByCode.values.sum()

    val isEmpty: Boolean
        get() = liveBuysFound + backfillBuysFound + captured + skipped + simulated + retriedAttempts + failed + abandoned + retriedByPerson == 0
}
