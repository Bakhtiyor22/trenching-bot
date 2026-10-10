package com.trenching.bot.marketdata.domain

import com.trenching.bot.marketdata.MarketCaptureWindow
import com.trenching.bot.marketdata.MarketCaptureWindowParameters
import com.trenching.bot.marketdata.MarketHistoryCaptureLimits

/** What a capture accepts as its windows and window parameters, both in a request and in a saved manifest. */
internal object MarketWindowRules {
    private val SLOT_PARAMETER = 0L..1000L
    private val HORIZON = 1L..MarketHistoryCaptureLimits.MAX_HORIZON_SLOTS

    fun canonical(windows: List<MarketCaptureWindow>): List<MarketCaptureWindow> =
        windows.sortedWith(compareBy({ it.baseMint.value }, { it.fromSlot.value }))

    /** Windows of one mint must be merged before capture, so canonical windows of a mint never overlap or touch. */
    fun isDisjoint(canonical: List<MarketCaptureWindow>): Boolean = canonical.zipWithNext().none { (earlier, later) ->
        earlier.baseMint == later.baseMint && later.fromSlot.value <= Math.addExact(earlier.toSlot.value, 1)
    }

    fun isValid(parameters: MarketCaptureWindowParameters): Boolean =
        parameters.horizonSlots in HORIZON && parameters.maxAgeSlots in SLOT_PARAMETER
}
