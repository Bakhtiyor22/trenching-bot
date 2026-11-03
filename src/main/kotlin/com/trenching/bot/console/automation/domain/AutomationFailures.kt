package com.trenching.bot.console.automation.domain

/** Failure codes automation sets on an item itself; a failed capture call stores that call's code instead. */
object AutomationFailures {
    const val MISSING_BLOCK_TIME = "MISSING_BLOCK_TIME"

    /** The capture console's code for a deleted capture session or operation; the item's capture can never go on. */
    const val CAPTURE_NOT_FOUND = "CAPTURE_NOT_FOUND"

    /** Every sweep attempt of the item failed unexpectedly. */
    const val SWEEP_FAILED = "SWEEP_FAILED"

    /** The longest failure message an item stores (plan Rule 17); a longer one is cut. */
    const val MAX_MESSAGE_LENGTH = 512

    fun message(text: String?): String? = text?.take(MAX_MESSAGE_LENGTH)
}
