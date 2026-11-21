package com.trenching.bot.chaincapture

/** Stop and failure categories that more than one class produces or recognises. */
object CaptureCategory {
    const val RATE_LIMITED = "RATE_LIMITED"
    const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    const val STOP_REQUESTED = "STOP_REQUESTED"

    /** The provider has no block for the slot: its leader skipped it, or the ledger jumped past it. */
    const val SLOT_SKIPPED = "SLOT_SKIPPED"

    /** Categories a caller reads as the provider's answer, not as a failure: a skipped slot is "no block", an unsupported version is "no such transaction". */
    val ANSWERS: Set<String> = setOf(SLOT_SKIPPED, UNSUPPORTED_VERSION)
}
