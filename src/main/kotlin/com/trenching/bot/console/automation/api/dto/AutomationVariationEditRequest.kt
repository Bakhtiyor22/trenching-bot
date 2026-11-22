package com.trenching.bot.console.automation.api.dto

import com.trenching.bot.console.api.dto.ConsoleConfigRequest

/** Both are nullable so a missing one reaches the checks as a refusal naming it; [config] is the variation's complete configuration. */
data class AutomationVariationEditRequest(val requestId: String? = null, val config: ConsoleConfigRequest? = null)
