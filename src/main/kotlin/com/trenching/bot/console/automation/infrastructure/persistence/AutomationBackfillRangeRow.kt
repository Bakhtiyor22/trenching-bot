package com.trenching.bot.console.automation.infrastructure.persistence

import java.time.Instant

/** The range a backfill covers, after clipping. */
interface AutomationBackfillRangeRow {
    val rangeStart: Instant
    val rangeEnd: Instant
}
