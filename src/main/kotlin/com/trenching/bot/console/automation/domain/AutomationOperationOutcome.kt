package com.trenching.bot.console.automation.domain

import com.trenching.bot.console.capture.CaptureNames

/** What a capture operation's state means for its item (plan Rule 17). */
enum class AutomationOperationOutcome {
    /** The item moves on to its next step. */
    SUCCEEDED,

    /** Running, or waiting for its own automatic retry: the capture worker stays reserved for it. */
    RUNNING,

    /** Paused from the console; the item waits until it is resumed or cancelled there. */
    PAUSED,

    /** The step runs again under its next attempt, or the item fails. */
    FAILED;

    companion object {
        fun of(state: String): AutomationOperationOutcome = when (state) {
            CaptureNames.SUCCEEDED -> SUCCEEDED
            CaptureNames.RUNNING, CaptureNames.WAITING_RETRY -> RUNNING
            CaptureNames.PAUSED -> PAUSED
            CaptureNames.FAILED, CaptureNames.INCOMPLETE, CaptureNames.INTERRUPTED, CaptureNames.CANCELLED, CaptureNames.BLOCKED -> FAILED
            else -> throw IllegalStateException("Unknown capture operation state=$state")
        }
    }
}
