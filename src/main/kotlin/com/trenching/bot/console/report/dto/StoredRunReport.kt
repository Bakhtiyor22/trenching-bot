package com.trenching.bot.console.report.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal

/** The parts of replay's stored `SimulationReport` JSON that no store port returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StoredRunReport(
    val configuration: StoredConfiguration,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StoredConfiguration(
    val strategyName: String,
    val strategyVersion: String,
    val notional: BigDecimal,
    val feeBps: Int,
    val slippageBps: Int,
    val maxWaitSlots: Long,
    val maxAgeSlots: Long,
    val holdingSlots: Long,
    val quoteMint: StoredAddress,
)

data class StoredAddress(val value: String)

