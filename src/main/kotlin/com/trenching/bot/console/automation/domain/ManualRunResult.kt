package com.trenching.bot.console.automation.domain

/** What a "run now" request did (plan Rule 29): queued a pass, or found one already queued or running and added nothing. */
data class ManualRunResult(val queued: Boolean, val alreadyQueued: Boolean) {
    companion object {
        val QUEUED = ManualRunResult(queued = true, alreadyQueued = false)
        val ALREADY_QUEUED = ManualRunResult(queued = false, alreadyQueued = true)
    }
}
