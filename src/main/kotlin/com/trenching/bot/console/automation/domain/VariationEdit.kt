package com.trenching.bot.console.automation.domain

import com.trenching.bot.console.api.dto.ConsoleConfigRequest

/**
 * One edit request: the complete configuration the edited variation is replaced with, as the caller sent it, null when it sent none.
 * The service validates it at its place in the order of checks, after the repeated request and the unknown label.
 * [requestId] makes the request idempotent.
 */
data class VariationEdit(val requestId: String, val config: ConsoleConfigRequest?)

/** [created] is false when the same request id was stored by an earlier edit, and [variation] is then the variation that edit created. */
data class VariationEditResult(val variation: VariationView, val created: Boolean)
