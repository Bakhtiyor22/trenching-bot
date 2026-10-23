package com.trenching.bot.console.report.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal

/** The parts of replay's stored `SimulationReport` JSON that no store port returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StoredRunReport(
    val configuration: StoredConfiguration,
)

/** The timed fields are absent from a snapshot stored before engine dip-paper-v4, so they default to null (untimed). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StoredConfiguration(
    val strategyName: String,
    val strategyVersion: String,
    val notional: BigDecimal,
    val maxAgeSlots: Long,
    val dipBps: Int,
    val takeProfitBps: Int,
    val stopLossBps: Int,
    val minMarketCapUsd: BigDecimal,
    val horizonSlots: Long,
    val latencySlots: Long,
    val transactionCostSol: BigDecimal,
    val quoteMint: StoredAddress,
    val dipWindowMs: Long? = null,
    val maxHoldMs: Long? = null,
    val hardExitStartMs: Long? = null,
)

data class StoredAddress(val value: String)

