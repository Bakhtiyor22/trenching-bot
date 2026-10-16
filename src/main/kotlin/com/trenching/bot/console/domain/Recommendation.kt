package com.trenching.bot.console.domain

enum class RecommendationTarget { CAPTURE, RUN }

/** The configuration [field] a recommendation would set, and to what. */
data class RecommendationSetting(val field: String, val value: String)

/**
 * A setup suggestion identified by [id]; the console holds its copy. Each [facts] value is a string or a whole number, the
 * evidence the copy may quote.
 */
data class Recommendation(
    val id: String,
    val appliesTo: RecommendationTarget,
    val setting: RecommendationSetting?,
    val facts: Map<String, Any>,
    val caveatCode: String?,
)
