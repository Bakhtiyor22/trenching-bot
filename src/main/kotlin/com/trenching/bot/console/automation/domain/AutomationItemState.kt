package com.trenching.bot.console.automation.domain

/**
 * A discovered buy's place in the pipeline (plan Rule 13). [terminal] states are never advanced again; `WAITING` and
 * `BLOCKED_BUDGET` wait for a later tick.
 */
enum class AutomationItemState(val terminal: Boolean) {
    DISCOVERED(false),
    WAITING(false),
    BLOCKED_BUDGET(false),
    CAPTURING(false),
    CAPTURED(false),
    SIMULATED(true),
    FAILED(true),
    ABANDONED(true),
    SKIPPED_NOT_FIRST_BUY(true);

    companion object {
        val TERMINAL: Set<AutomationItemState> = entries.filter { it.terminal }.toSet()

        /** The states the capture pipeline drives; a `CAPTURED` item waits for the sweep. */
        val IN_CAPTURE: Set<AutomationItemState> = setOf(DISCOVERED, WAITING, BLOCKED_BUDGET, CAPTURING)

        /** The states that let a closing session close: with capture off (phase 0) nothing advances a `DISCOVERED` item. */
        fun settled(captureEnabled: Boolean): Set<AutomationItemState> = if (captureEnabled) TERMINAL else TERMINAL + DISCOVERED
    }
}
