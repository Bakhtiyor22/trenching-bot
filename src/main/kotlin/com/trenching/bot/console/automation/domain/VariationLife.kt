package com.trenching.bot.console.automation.domain

import java.time.Instant

/**
 * The span of a variation's life (plan Rule 9): from [activeFrom] (null for a seeded variation, which has been there since the
 * plan began) through [archivedAt] (null while it is active). It covers a trading day when the day is no earlier than the day
 * [activeFrom] falls on and no later than the day [archivedAt] falls on.
 */
data class VariationLife(val activeFrom: Instant?, val archivedAt: Instant?) {
    fun covers(day: TradingDay, calendar: TradingCalendar): Boolean =
        (activeFrom == null || calendar.dayOf(activeFrom) <= day) && (archivedAt == null || day <= calendar.dayOf(archivedAt))
}
