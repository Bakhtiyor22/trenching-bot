package com.trenching.bot.console.automation.domain

import com.trenching.bot.strategy.domain.SimulationConfig

/** What may be edited and how the replacement is named (plan Rules 1 and 2, Open Question 5). */
object VariationEditRules {
    /** The agreed configuration, which every other variation is compared with and so is never edited. */
    const val BASELINE_LABEL = "V00"

    /** The width of the stored request id. */
    const val MAX_REQUEST_ID_LENGTH = 64

    private const val REQUEST_ID = "requestId"
    private const val LABEL_PREFIX = "V"
    private const val LABEL_DIGITS = 2

    fun requireRequestId(requestId: String): String {
        if (requestId.isBlank()) throw InvalidAutomationRequestException(REQUEST_ID, "is required")
        if (requestId.length > MAX_REQUEST_ID_LENGTH) throw InvalidAutomationRequestException(REQUEST_ID, "must have at most $MAX_REQUEST_ID_LENGTH characters")
        return requestId
    }

    /** The label of the variation with sequence number [seq]: `V` and the number, two digits at least. Labels are never reused because sequence numbers never are. */
    fun labelOf(seq: Int): String = LABEL_PREFIX + seq.toString().padStart(LABEL_DIGITS, '0')

    /**
     * Refuses an edit of [target] to [config] unless it is allowed, naming the first reason: [target] is the baseline, is archived,
     * already has [config], or another of the [active] variations does.
     */
    fun requireEditable(target: Variation, config: SimulationConfig, active: List<Variation>) {
        if (target.label == BASELINE_LABEL) throw VariationBaselineException(target.label)
        if (target.state == VariationState.ARCHIVED) throw VariationArchivedException(target.label)
        if (target.config == config) throw VariationUnchangedException(target.label)
        active.firstOrNull { it.config == config }?.let { throw VariationDuplicateException(it.label) }
    }
}
