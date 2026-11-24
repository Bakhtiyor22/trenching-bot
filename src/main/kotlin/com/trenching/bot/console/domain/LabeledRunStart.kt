package com.trenching.bot.console.domain

import com.trenching.bot.replay.domain.RunSummary

/** A started run with the [variations] it belongs to; replay's own start result does not know about them. */
data class LabeledRunStart(val run: RunSummary, val created: Boolean, val variations: List<String>)
