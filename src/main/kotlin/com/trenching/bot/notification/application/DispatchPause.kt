package com.trenching.bot.notification.application

import java.time.Duration

/** The wait between two sends of one run, so a burst stays under Telegram's per-chat rate. */
fun interface DispatchPause {
    /** Waits; false when the thread was interrupted and the run should stop. */
    fun between(): Boolean

    companion object {
        val ONE_SECOND = DispatchPause {
            try {
                Thread.sleep(Duration.ofSeconds(1))
                true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
    }
}
