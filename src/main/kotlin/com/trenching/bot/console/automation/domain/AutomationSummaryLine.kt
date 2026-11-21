package com.trenching.bot.console.automation.domain

/** The hourly summary as one log line: a parameterized sentence ([format] with [arguments]), then `key=value` [fields] whose null values are left out. */
data class AutomationSummaryLine(
    val level: AutomationLogLevel,
    val format: String,
    val arguments: List<Any>,
    val fields: List<Pair<String, Any?>>,
)
