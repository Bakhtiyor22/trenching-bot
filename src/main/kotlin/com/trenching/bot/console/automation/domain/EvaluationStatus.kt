package com.trenching.bot.console.automation.domain

/** A sweep entry's verdict over its window of sessions (plan Rule 25); a lower [rank] is better. */
enum class EvaluationStatus(val rank: Int) {
    SUCCESS(0),
    NO_SUCCESS(1),
    NO_RESULT(2),
}
