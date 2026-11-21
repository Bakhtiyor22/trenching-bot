package com.trenching.bot.console.automation.domain

import java.time.Instant

/**
 * The item a change belongs to, as it stood once the change was applied: the buy's identity and context a log line names.
 * [sweepRuns] is the number of sweep rows of the item and [sweepRefused] how many of them the replay refused, read together only for an
 * item that was just simulated.
 */
data class AutomationItemSubject(
    val itemId: String,
    val mint: String,
    val wallet: String,
    val backfillId: String?,
    val blockTime: Instant?,
    val sessionId: String,
    val datasetId: String?,
    val classification: String?,
    val requestsUsed: Int?,
    val sweepRuns: Int?,
    val sweepRefused: Int? = null,
)
