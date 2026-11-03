package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemState

/** One backfill's number of items in one state. */
interface AutomationBackfillStateCountRow {
    val backfillId: String
    val state: AutomationItemState
    val items: Long
}

/** One backfill's provider requests, summed over its items. */
interface AutomationBackfillRequestsRow {
    val backfillId: String
    val requests: Long
}
