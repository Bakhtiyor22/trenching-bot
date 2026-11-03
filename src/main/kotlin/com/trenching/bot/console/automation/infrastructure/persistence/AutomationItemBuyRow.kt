package com.trenching.bot.console.automation.infrastructure.persistence

import java.time.Instant

/** One item's id and buy time, null for a buy without a block time. */
interface AutomationItemBuyRow {
    val id: String
    val blockTime: Instant?
}
