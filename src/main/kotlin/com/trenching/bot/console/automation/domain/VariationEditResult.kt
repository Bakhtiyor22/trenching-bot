package com.trenching.bot.console.automation.domain

/** [created] is false when the same request id was stored by an earlier edit, and [variation] is then the variation that edit created. */
data class VariationEditResult(val variation: VariationView, val created: Boolean)
