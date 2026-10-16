package com.trenching.bot.console.api.dto

/** [appliesTo] is `CAPTURE` or `RUN`; each [facts] value is a string or a whole number. */
data class RecommendationResponse(
    val id: String,
    val appliesTo: String,
    val setting: RecommendationSettingResponse?,
    val facts: Map<String, Any>,
    val caveatCode: String?,
)

data class RecommendationSettingResponse(
    val field: String,
    val value: String,
)
