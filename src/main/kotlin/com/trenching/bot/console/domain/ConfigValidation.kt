package com.trenching.bot.console.domain

import com.trenching.bot.strategy.domain.SimulationConfig

sealed interface ConfigValidation {
    data class Valid(val config: SimulationConfig) : ConfigValidation
    /** One message per offending field, keyed by its JSON property name. */
    data class Invalid(val fields: Map<String, String>) : ConfigValidation
}
