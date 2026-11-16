package com.trenching.bot.replay.domain

/**
 * One page of the search-index backfill. [lastId] is the last run looked at, or null when no run was left to index.
 * [skipped] runs have no dataset and were marked indexed without tokens; [failed] runs were left unindexed to retry.
 */
data class RunIndexBackfillPage(val lastId: String?, val indexed: Int, val skipped: Int, val failed: Int)
