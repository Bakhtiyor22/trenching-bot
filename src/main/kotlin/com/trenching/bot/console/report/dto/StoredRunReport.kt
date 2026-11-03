package com.trenching.bot.console.report.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.trenching.bot.strategy.domain.PriceMeasure
import java.math.BigDecimal

/** The parts of replay's stored `SimulationReport` JSON that no store port returns. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StoredRunReport(
    val configuration: StoredConfiguration,
)

/**
 * The timed fields are absent from a snapshot stored before engine dip-paper-v4, so they default to null (untimed); the price
 * measure and the wallet-sell rule are absent before dip-paper-v5, so they default to the behaviour of dip-paper-v4; the minimum
 * signal size is absent before dip-paper-v6, so it defaults to none.
 */
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
    val priceMeasure: PriceMeasure = PriceMeasure.TRADE_AVERAGE,
    val followWalletSells: Boolean = false,
    val minSignalSol: BigDecimal? = null,
)

data class StoredAddress(val value: String)

