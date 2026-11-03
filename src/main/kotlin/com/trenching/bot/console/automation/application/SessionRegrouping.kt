package com.trenching.bot.console.automation.application

import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity

/**
 * What [OpenSessions.regroup] changed in one session: each session [split] off it with the ids of the items that move there,
 * the session itself when no item is left in it ([emptied]), or when only buys without a block time are left and it therefore
 * stopped taking buys ([closing]).
 */
data class SessionRegrouping(
    val split: Map<AutomationSessionEntity, List<String>> = emptyMap(),
    val emptied: AutomationSessionEntity? = null,
    val closing: AutomationSessionEntity? = null,
)
