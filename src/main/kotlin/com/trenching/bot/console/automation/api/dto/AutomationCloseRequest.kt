package com.trenching.bot.console.automation.api.dto

/** [requestId] is required; it is nullable here so a missing one reaches the service's validation as a 400. */
data class AutomationCloseRequest(val requestId: String? = null)
