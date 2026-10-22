package com.trenching.bot.console.domain

import java.time.Instant

/** The chart request exactly as received; `ChartParameters` validates it. */
data class ChartQuery(
    val pair: String?,
    val from: Instant?,
    val to: Instant?,
    val maxBuckets: Int?,
    val markerLimit: Int?,
    val wallets: List<String>,
    val cursor: String?,
    val tickLimit: Int?,
)
