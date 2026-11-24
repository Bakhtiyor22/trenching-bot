package com.trenching.bot.console.automation.infrastructure.persistence

/** The label of a sweep entry that got the run [runId]. */
interface AutomationRunLabelRow {
    val runId: String
    val label: String
}
