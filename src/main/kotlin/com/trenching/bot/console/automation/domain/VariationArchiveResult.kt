package com.trenching.bot.console.automation.domain

/** [archived] is false when the variation already was archived, and then nothing was written. */
data class VariationArchiveResult(val variation: VariationView, val archived: Boolean)
