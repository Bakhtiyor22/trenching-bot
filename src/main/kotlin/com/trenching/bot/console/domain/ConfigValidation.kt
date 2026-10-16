package com.trenching.bot.console.domain

import com.trenching.bot.strategy.domain.SimulationConfig

/** The most specific reason a configuration was refused; a conflict outranks a bad percent, which outranks any other error. */
enum class ConfigErrorCode(val message: String) {
    CONFIG_INVALID("Simulation configuration is invalid"),
    INVALID_PERCENT("A percent setting is invalid"),
    CONFLICTING_FIELDS("A percent setting disagrees with its basis point field"),
}

sealed interface ConfigValidation {
    data class Valid(val config: SimulationConfig) : ConfigValidation
    /** One message per offending field, keyed by its JSON property name. */
    data class Invalid(val fields: Map<String, String>, val code: ConfigErrorCode = ConfigErrorCode.CONFIG_INVALID) : ConfigValidation
}
