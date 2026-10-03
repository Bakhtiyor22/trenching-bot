package com.trenching.bot.replay.domain

/** A dataset ready to store: its summary and the canonical v1 JSON it can be reloaded from. */
data class DatasetEntry(val summary: DatasetSummary, val snapshot: String)
