package com.trenching.bot.console.automation.domain

/** What taking an item's next step did on one tick of the pipeline. */
sealed interface AutomationStepOutcome {
    /** A capture operation started, so no other one starts on this tick. */
    data object OperationStarted : AutomationStepOutcome

    /** The step moved the item on, or left it waiting, without starting an operation. */
    data object NoOperation : AutomationStepOutcome

    /**
     * The step waits for [kind] (see [AutomationHoldKind]) and the item stays as it is, with no attempt charged, until the hold ends:
     * the request cap, a backfill's reserve, or a capture console that is not configured. [context] says why, for the hold's one line.
     */
    data class Held(val kind: AutomationHoldKind, val context: AutomationHoldContext) : AutomationStepOutcome
}
