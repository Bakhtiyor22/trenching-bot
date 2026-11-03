package com.trenching.bot.console.automation.domain

/** What taking an item's next step did on one tick of the pipeline. */
sealed interface AutomationStepOutcome {
    /** A capture operation started, so no other one starts on this tick. */
    data object OperationStarted : AutomationStepOutcome

    /** The step moved the item on, or left it waiting, without starting an operation. */
    data object NoOperation : AutomationStepOutcome

    /**
     * The capture console refused the step's operation as not configured (no RPC URL, or reference rates switched off). The
     * item waits as it is, with no attempt charged, until the backend is configured.
     */
    data class NotConfigured(val step: AutomationStep, val reason: String) : AutomationStepOutcome
}
