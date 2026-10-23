package com.trenching.bot.console.domain

import com.trenching.bot.common.MintAddress
import com.trenching.bot.strategy.domain.SimulationConfig
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The agreed `decu-dip` v2 strategy mapped onto the only runnable engine, `dip-entry` v1, field by field. Pinned to the
 * spec by `AgreedStrategyPresetTest`; values the spec does not set are the engine's `mvp()` defaults.
 */
object AgreedStrategyPreset {
    const val ID = "decu-dip-v2-on-dip-entry-v1"
    const val ENGINE = "dip-entry v1"

    val SOURCE = PresetSource("docs/strategy/decu-dip-v2-spec.md", "decu-dip", 2, LocalDate.of(2026, 10, 11))

    private val ENGINE_DEFAULTS = SimulationConfig.mvp()

    val CONFIG: SimulationConfig = SimulationConfig.of(
        notional = BigDecimal("0.1"),
        maxAgeSlots = ENGINE_DEFAULTS.maxAgeSlots,
        dipBps = 2000,
        takeProfitBps = 5500,
        stopLossBps = 4000,
        minMarketCapUsd = BigDecimal("10000"),
        horizonSlots = ENGINE_DEFAULTS.horizonSlots,
        latencySlots = 1,
        transactionCostSol = BigDecimal("0.001"),
        quoteMint = MintAddress.WRAPPED_SOL,
    )

    val FIELDS: List<PresetField> = listOf(
        PresetField("dipBps", PresetFieldStatus.AGREED, "P6", null),
        PresetField("takeProfitBps", PresetFieldStatus.AGREED, "P9", null),
        PresetField("stopLossBps", PresetFieldStatus.AGREED, "P10", null),
        PresetField("notional", PresetFieldStatus.AGREED, "P12", "CURVE_PAYMENT_FEES_ON_TOP"),
        PresetField("transactionCostSol", PresetFieldStatus.AGREED, "entryPriorityFeeSol", "NETWORK_FEE_NOT_ADDED"),
        PresetField("quoteMint", PresetFieldStatus.AGREED, "P15", null),
        PresetField("minMarketCapUsd", PresetFieldStatus.AGREED, "P5", null),
        PresetField("latencySlots", PresetFieldStatus.ASSUMPTION, null, "REPLAY_LATENCY_ASSUMED"),
        PresetField("horizonSlots", PresetFieldStatus.MISSING, null, "NO_SINGLE_WINDOW_IN_SPEC"),
        PresetField("maxAgeSlots", PresetFieldStatus.ENGINE_DEFAULT, null, null),
    )

    val UNSUPPORTED_RULES: List<UnsupportedSpecRule> = listOf(
        UnsupportedSpecRule("P2", "FIRST_EVER_BUY"),
        UnsupportedSpecRule("P3", "SIGNAL_OVER_1_9_SOL"),
        // dip-entry v1 compares each trade's average execution price; the spec compares the curve's price after it.
        UnsupportedSpecRule("Section 5", "POST_TRADE_PRICE"),
        UnsupportedSpecRule("P7", "DIP_WINDOW_30_SECONDS"),
        UnsupportedSpecRule("P8", "ENTRY_SLIPPAGE_15_PERCENT"),
        UnsupportedSpecRule("P11", "TIMED_EXIT"),
        UnsupportedSpecRule("Section 8", "EXIT_RETRIES_AND_UNRESOLVED"),
        UnsupportedSpecRule("P13", "ONE_POSITION_INCLUDING_IN_FLIGHT"),
    )

    val PRESET = RunPreset(ID, SOURCE, ENGINE, CONFIG, FIELDS, UNSUPPORTED_RULES)
}
