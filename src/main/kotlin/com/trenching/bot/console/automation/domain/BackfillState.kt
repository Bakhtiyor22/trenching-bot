package com.trenching.bot.console.automation.domain

/**
 * Where a backfill stands (plan Rules 36-39). [active] states hold the wallet's single backfill slot: the scan runs in
 * [QUEUED] and [SCANNING], and the items it found are captured in [CAPTURING]. A backfill whose scan the budget cut short is
 * [STOPPED_BUDGET] at once and its found items still run; one whose items the budget stopped becomes [STOPPED_BUDGET] when
 * they are all terminal.
 */
enum class BackfillState(val active: Boolean) {
    QUEUED(true),
    SCANNING(true),
    CAPTURING(true),
    DONE(false),
    STOPPED_BUDGET(false),
    CANCELLED(false);

    companion object {
        val ACTIVE: Set<BackfillState> = entries.filter { it.active }.toSet()

        /** The states in which discovery still reads the wallet's history. */
        val SCANNING_STATES: Set<BackfillState> = setOf(QUEUED, SCANNING)

        /** The states whose items the pipeline captures: the scan is over, so no earlier buy of a mint can still replace an item. */
        val CAPTURING_STATES: Set<BackfillState> = setOf(CAPTURING, STOPPED_BUDGET)
    }
}
