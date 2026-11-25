package com.trenching.bot.console.domain

import com.trenching.bot.replay.domain.RunSummary

/** A started run with the [variations] it belongs to and its [outcomes]; replay's own start result knows neither. */
data class LabeledRunStart(val run: RunSummary, val created: Boolean, val variations: List<String>, val outcomes: List<RunOutcome>)
