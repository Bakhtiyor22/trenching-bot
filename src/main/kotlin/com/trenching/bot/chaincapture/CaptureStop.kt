package com.trenching.bot.chaincapture

/**
 * Why one acquisition invocation stopped early: a [CaptureBudgetException] or [CaptureProviderException] category such as
 * `DEADLINE`, `REQUESTS` or `RATE_LIMITED`. The first stop wins; null means the invocation was not interrupted.
 */
class CaptureStop {
    var reason: String? = null
        private set

    fun record(category: String) {
        if (reason == null) reason = category
    }
}
