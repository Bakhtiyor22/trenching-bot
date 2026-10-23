package com.trenching.bot.strategy.domain

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MintAddress
import java.math.BigDecimal

/**
 * One dip-entry run's parameters, per the dip-entry-strategy ADR: buy [dipBps] below the wallet's first price when that
 * buy's market cap is above [minMarketCapUsd] (the dip's is not checked), then exit [takeProfitBps] above or [stopLossBps]
 * below the entry, all within [horizonSlots] of the wallet's buy. Per the bonding-curve-execution ADR, each entry and exit
 * fills [latencySlots] or more slots after its trigger and pays [transactionCostSol] on top of the curve's own fees.
 *
 * The timed fields are spec P7 and P11's block-time clocks; null keeps the slot-only behaviour. [dipWindowMs] bounds the dip
 * watch from the buy's block time; [maxHoldMs] (from the fill) and [hardExitStartMs] (from the buy) bound a position and are
 * set together.
 */
data class SimulationConfig private constructor(
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
    val quoteMint: MintAddress,
    val dipWindowMs: Long?,
    val maxHoldMs: Long?,
    val hardExitStartMs: Long?,
) {
    companion object {
        const val STRATEGY_NAME = "dip-entry"
        const val STRATEGY_VERSION = "1"

        fun of(
            notional: BigDecimal, maxAgeSlots: Long, dipBps: Int, takeProfitBps: Int, stopLossBps: Int,
            minMarketCapUsd: BigDecimal, horizonSlots: Long, latencySlots: Long, transactionCostSol: BigDecimal,
            strategyName: String = STRATEGY_NAME, strategyVersion: String = STRATEGY_VERSION,
            quoteMint: MintAddress = MintAddress.WRAPPED_SOL,
            dipWindowMs: Long? = null, maxHoldMs: Long? = null, hardExitStartMs: Long? = null,
        ): SimulationConfig {
            require(notional.signum() > 0 && notional.stripTrailingZeros().scale() <= SimulationConfigBounds.NOTIONAL_DECIMALS) {
                "Notional must be a positive whole number of lamports notional=$notional"
            }
            val normalizedMarketCap = DecimalPolicy.scale(minMarketCapUsd)
            val normalizedTransactionCost = DecimalPolicy.scale(transactionCostSol)
            require(dipBps in SimulationConfigBounds.LEVEL_BASIS_POINTS && takeProfitBps in SimulationConfigBounds.LEVEL_BASIS_POINTS &&
                stopLossBps in SimulationConfigBounds.LEVEL_BASIS_POINTS) { "Invalid dip, take-profit or stop-loss level" }
            require(normalizedMarketCap.signum() >= 0 && normalizedMarketCap <= SimulationConfigBounds.MAX_MIN_MARKET_CAP_USD) {
                "Invalid minimum market cap"
            }
            require(maxAgeSlots in SimulationConfigBounds.MAX_AGE_SLOTS && horizonSlots in SimulationConfigBounds.HORIZON_SLOTS &&
                latencySlots in SimulationConfigBounds.LATENCY_SLOTS) { "Invalid slot bounds" }
            require(normalizedTransactionCost.signum() >= 0 && normalizedTransactionCost <= SimulationConfigBounds.MAX_TRANSACTION_COST_SOL) {
                "Invalid transaction cost transactionCostSol=$transactionCostSol"
            }
            require(dipWindowMs == null || dipWindowMs in SimulationConfigBounds.DIP_WINDOW_MS) { "Invalid dip window dipWindowMs=$dipWindowMs" }
            require((maxHoldMs == null) == (hardExitStartMs == null)) {
                "The maximum hold and the hard exit start are set together maxHoldMs=$maxHoldMs hardExitStartMs=$hardExitStartMs"
            }
            require(listOfNotNull(maxHoldMs, hardExitStartMs).all { it in SimulationConfigBounds.EXIT_CLOCK_MS }) {
                "Invalid exit clock maxHoldMs=$maxHoldMs hardExitStartMs=$hardExitStartMs"
            }
            require(strategyName.isNotBlank() && strategyVersion.isNotBlank()) { "Missing strategy identity" }
            return SimulationConfig(strategyName, strategyVersion, DecimalPolicy.scale(notional), maxAgeSlots, dipBps, takeProfitBps,
                stopLossBps, normalizedMarketCap, horizonSlots, latencySlots, normalizedTransactionCost, quoteMint, dipWindowMs, maxHoldMs,
                hardExitStartMs)
        }

        /**
         * The user's strategy: 0.1 SOL per entry, 15% dip, $10k minimum market cap, +30% take profit, -15% stop loss,
         * 30 minutes, filling one slot after each trigger at 0.001 SOL per transaction.
         */
        fun mvp(): SimulationConfig =
            of(BigDecimal("0.1"), MAX_AGE_SLOTS, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"))

        /**
         * The agreed `decu-dip` v2 spec (`docs/strategy/decu-dip-v2-spec.md`): 0.1 SOL per entry, a 20% dip within 30 s of the
         * wallet's buy (P6, P7), $10k minimum market cap (P5), +55% take profit, -40% stop loss (P9, P10), and a time exit after
         * 3 minutes held or 4 minutes from the buy, whichever is first (P11). The horizon only bounds the recording.
         */
        fun agreed(): SimulationConfig =
            of(BigDecimal("0.1"), MAX_AGE_SLOTS, 2000, 5500, 4000, BigDecimal("10000"), 4500, 1, BigDecimal("0.001"),
                dipWindowMs = 30_000, maxHoldMs = 180_000, hardExitStartMs = 240_000)

        /** The engine's own choice: the spec sets no observation age. */
        private const val MAX_AGE_SLOTS = 2L
    }
}
