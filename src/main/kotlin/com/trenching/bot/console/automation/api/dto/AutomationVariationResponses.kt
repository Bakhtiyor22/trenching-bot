package com.trenching.bot.console.automation.api.dto

import com.trenching.bot.console.api.dto.ConfigResponse
import java.time.Instant

/**
 * One stored variation, keyed by [label]; [name] is its place in its edit line (`V03.1`) and equals [label] except for the
 * six edits stored before names existed. [differsFromAgreed] and [changedFromReplaced] name configuration properties, in the configuration
 * response's order; [changedFromReplaced] is empty when the variation replaced none. [activeFrom] null means since the plan began.
 */
data class AutomationVariationResponse(
    val planId: String,
    val label: String,
    val name: String,
    val state: String,
    val config: ConfigResponse,
    val differsFromAgreed: List<String>,
    val changedFromReplaced: List<String>,
    val replacesLabel: String?,
    val replacedByLabel: String?,
    val activeFrom: Instant?,
    val archivedAt: Instant?,
    val createdAt: Instant,
)

data class AutomationVariationListResponse(val planId: String, val items: List<AutomationVariationResponse>)
