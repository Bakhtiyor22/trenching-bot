package com.trenching.bot.console.automation.domain

import com.trenching.bot.walletactivity.WalletActivityLimits
import java.time.Duration

/** How much one tick of the sweep may do and one read may return, so each stays short and bounded. */
object AutomationLimits {
    /** Captured items swept per tick; each replays every entry of the plan. */
    const val SWEEP_ITEMS_PER_TICK = 2

    /** How long an item waits to be swept again after its first failed sweep; each further failure doubles it. */
    val SWEEP_RETRY_DELAY: Duration = Duration.ofMinutes(5)

    /** Items read per session, for its detail or a day's scoring; a night holds far fewer buys. */
    const val MAX_ITEMS_PER_SESSION = 1000

    /** The most provider requests one page of backfill discovery may cost (a page reads `limit` transactions plus two requests). */
    const val DISCOVERY_PAGE_REQUESTS = WalletActivityLimits.DEFAULT_LIMIT + 2L

    /** Sessions a cancelled backfill closes per read, so closing a long backfill's sessions stays bounded. */
    const val CANCEL_SESSIONS_PER_READ = 200

    /** Finished backfills read for a wallet's coverage; a backfill is made by hand, so a wallet has far fewer. */
    const val MAX_COVERING_BACKFILLS = 500

    /** Trading days one list of days or day verdicts covers. */
    const val MAX_DAYS_PER_LIST = 62
}
