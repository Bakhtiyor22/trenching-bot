package com.trenching.bot.console.automation.domain

import java.time.Instant

/** When scheduled discovery scans (plan Rules 28 and 29): at every interval, or only while the polling window is open. */
sealed interface DiscoveryMode {
    /** The mode's name in `/status`. */
    val label: String

    fun scansAt(instant: Instant): Boolean

    data object Always : DiscoveryMode {
        override val label: String = "ALWAYS"

        override fun scansAt(instant: Instant): Boolean = true
    }

    data class Window(val window: PollWindow) : DiscoveryMode {
        override val label: String = "WINDOW"

        override fun scansAt(instant: Instant): Boolean = window.contains(instant)
    }
}
