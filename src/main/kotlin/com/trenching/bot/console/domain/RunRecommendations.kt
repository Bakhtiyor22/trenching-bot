package com.trenching.bot.console.domain

import com.trenching.bot.strategy.domain.SimulationConfig

/** Run-setup recommendations as a pure function of the dataset's facts: the same facts always give the same list and order. */
object RunRecommendations {
    const val USE_AGREED_STRATEGY = "USE_AGREED_STRATEGY"
    const val ADD_LEGACY_COMPARISON = "ADD_LEGACY_COMPARISON"
    const val SINGLE_TOKEN_DATASET = "SINGLE_TOKEN_DATASET"
    const val COMPARISON_MAY_NEED_FULL_CAPTURE = "COMPARISON_MAY_NEED_FULL_CAPTURE"

    private const val PRESET_ID_FIELD = "presetId"
    private const val SINGLE_PAIR = 1

    fun of(pairCount: Int): List<Recommendation> = listOfNotNull(agreedStrategy(), legacyComparison(),
        singleToken(pairCount).takeIf { pairCount == SINGLE_PAIR })

    private fun agreedStrategy(): Recommendation {
        val preset = AgreedStrategyPreset.PRESET
        return Recommendation(USE_AGREED_STRATEGY, RecommendationTarget.RUN, RecommendationSetting(PRESET_ID_FIELD, preset.id),
            mapOf("unsupportedRuleCount" to preset.unsupportedRules.size,
                "missingFieldCount" to preset.fields.count { it.status == PresetFieldStatus.MISSING }), null)
    }

    /**
     * The legacy settings keep a position for the whole horizon, so on a capture cut by the `DECIDED` cutoff, which records
     * the agreed strategy's outcome, the comparison run can be refused for coverage.
     */
    private fun legacyComparison(): Recommendation {
        val legacy = SimulationConfig.mvp()
        return Recommendation(ADD_LEGACY_COMPARISON, RecommendationTarget.RUN, null,
            mapOf("dipPercent" to PercentConversion.toPercent(legacy.dipBps.toLong()),
                "takeProfitPercent" to PercentConversion.toPercent(legacy.takeProfitBps.toLong()),
                "stopLossPercent" to PercentConversion.toPercent(legacy.stopLossBps.toLong())), COMPARISON_MAY_NEED_FULL_CAPTURE)
    }

    private fun singleToken(pairCount: Int): Recommendation =
        Recommendation(SINGLE_TOKEN_DATASET, RecommendationTarget.RUN, null, mapOf("pairCount" to pairCount), null)
}
