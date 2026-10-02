package com.trenching.bot.common

/**
 * Upper bounds shared by the replay loader and every feature store, so a dataset
 * the loader accepts always fits the batches the stores will persist.
 */
object ReplayLimits {
    /** Wallet transactions or market observations in one dataset. */
    const val MAX_INPUT_RECORDS = 10_000

    /** Entry scenarios derived from one source trade (+1, +2, +3 slots). */
    const val MAX_SCENARIOS_PER_TRADE = 3

    /** Intents, orders, fills or positions in one run. */
    const val MAX_SCENARIO_RECORDS = MAX_INPUT_RECORDS * MAX_SCENARIOS_PER_TRADE

    /** Column widths of persisted source strings. */
    const val MAX_SOURCE_LENGTH = 128
    const val MAX_OBSERVATION_ID_LENGTH = 255
}
