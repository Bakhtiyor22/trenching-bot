package com.trenching.bot.console.automation.domain

import java.time.Instant

/** An item the pipeline acts on, with the buy facts its capture and sweep steps need. */
data class AutomationDueItem(
    val id: String,
    val sessionId: String,
    val wallet: String,
    val mint: String,
    val signature: String,
    val blockTime: Instant?,
    val progress: AutomationItemProgress,
)
