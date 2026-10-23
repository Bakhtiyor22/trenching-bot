package com.trenching.bot.console.domain

import com.trenching.bot.strategy.domain.SimulationConfig
import java.time.LocalDate

/**
 * The agreed `decu-dip` v2 strategy mapped onto the only runnable engine, `dip-entry` v1, field by field: the strategy's own
 * [SimulationConfig.agreed]. Pinned to the spec by `AgreedStrategyPresetTest`.
 */
object AgreedStrategyPreset {
    const val ID = "decu-dip-v2-on-dip-entry-v1"
    const val ENGINE = "dip-entry v1"

    val SOURCE = PresetSource("docs/strategy/decu-dip-v2-spec.md", "decu-dip", 2, LocalDate.of(2026, 10, 11))

    val CONFIG: SimulationConfig = SimulationConfig.agreed()

    val FIELDS: List<PresetField> = listOf(
        PresetField("dipBps", PresetFieldStatus.AGREED, "P6", null),
        PresetField("dipWindowMs", PresetFieldStatus.AGREED, "P7", null),
        PresetField("takeProfitBps", PresetFieldStatus.AGREED, "P9", null),
        PresetField("stopLossBps", PresetFieldStatus.AGREED, "P10", null),
        PresetField("maxHoldMs", PresetFieldStatus.AGREED, "P11", null),
        PresetField("hardExitStartMs", PresetFieldStatus.AGREED, "P11", null),
        PresetField("notional", PresetFieldStatus.AGREED, "P12", "CURVE_PAYMENT_FEES_ON_TOP"),
        PresetField("transactionCostSol", PresetFieldStatus.AGREED, "entryPriorityFeeSol", "NETWORK_FEE_NOT_ADDED"),
        PresetField("quoteMint", PresetFieldStatus.AGREED, "P15", null),
        PresetField("minMarketCapUsd", PresetFieldStatus.AGREED, "P5", null),
        PresetField("latencySlots", PresetFieldStatus.ASSUMPTION, null, "REPLAY_LATENCY_ASSUMED"),
        // The clocks end every cycle; the horizon only bounds how far a market is recorded.
        PresetField("horizonSlots", PresetFieldStatus.ENGINE_DEFAULT, null, "RECORDING_BOUND_ONLY"),
        PresetField("maxAgeSlots", PresetFieldStatus.ENGINE_DEFAULT, null, null),
    )

    val UNSUPPORTED_RULES: List<UnsupportedSpecRule> = listOf(
        UnsupportedSpecRule("P2", "FIRST_EVER_BUY"),
        UnsupportedSpecRule("P3", "SIGNAL_OVER_1_9_SOL"),
        // dip-entry v1 compares each trade's average execution price; the spec compares the curve's price after it.
        UnsupportedSpecRule("Section 5", "POST_TRADE_PRICE"),
        UnsupportedSpecRule("P8", "ENTRY_SLIPPAGE_15_PERCENT"),
        // A paper sale lands at its first attempt for one transaction cost: no slippage steps, fee escalation or fee cap.
        UnsupportedSpecRule("Section 8", "EXIT_RETRY_ECONOMICS"),
        UnsupportedSpecRule("P13", "ONE_POSITION_INCLUDING_IN_FLIGHT"),
    )

    val PRESET = RunPreset(ID, SOURCE, ENGINE, CONFIG, FIELDS, UNSUPPORTED_RULES)
}
