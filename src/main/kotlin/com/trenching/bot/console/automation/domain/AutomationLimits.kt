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

    /** Variations read for a plan, active and archived; a plan grows by one per edit, by hand. */
    const val MAX_VARIATIONS_PER_PLAN = 500

    /** History events read for one item; an item has about twenty, its sweep entries and a few retries included. */
    const val MAX_EVENTS_PER_ITEM = 1000

    /** Items read per page of ids when history is started for the items that predate it; each one then gets a transaction of its own. */
    const val HISTORY_START_ITEMS_PER_PAGE = 200

    /** How long a hold must stay clear, with no item held, before it is said to be over: a window at the cap admits one operation, then fills again. */
    val HOLD_SETTLE: Duration = Duration.ofHours(1)

    /** How often the activity summary is logged, as the scheduler reads it (ISO-8601): every hour. */
    const val SUMMARY_INTERVAL = "PT1H"
}
