package com.trenching.bot.console.automation.domain

enum class AutomationLogLevel { DEBUG, INFO, WARN }

/** What a counted line adds to [AutomationActivityCounts]. */
enum class AutomationActivityKind {
    LIVE_FOUND,
    BACKFILL_FOUND,
    CAPTURED,
    SKIPPED,
    SIMULATED,
    RETRIED_ATTEMPT,
    FAILED,
    ABANDONED,
    RETRIED_BY_PERSON,
}

/**
 * One log line about an item change: a parameterized sentence ([format] with [arguments]), then `key=value` [fields], none of them
 * null. [activity] is what the line counts as, with the failure [code] for a failed buy.
 */
data class AutomationItemLogLine(
    val level: AutomationLogLevel,
    val format: String,
    val arguments: List<Any>,
    val fields: List<Pair<String, Any>>,
    val activity: AutomationActivityKind? = null,
    val code: String? = null,
)
