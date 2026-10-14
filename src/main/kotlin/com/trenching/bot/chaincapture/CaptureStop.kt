package com.trenching.bot.chaincapture

import java.time.Duration

/**
 * Why one acquisition invocation stopped early: a [CaptureBudgetException] or [CaptureProviderException] category such as
 * `DEADLINE`, `REQUESTS` or `RATE_LIMITED`. The first stop wins; null means the invocation was not interrupted.
 */
class CaptureStop {
    var reason: String? = null
        private set
    var retryAfter: Duration? = null
        private set

    fun record(category: String, retryAfter: Duration? = null) {
        if (reason == null) {
            reason = category
            this.retryAfter = retryAfter
        }
    }
}
