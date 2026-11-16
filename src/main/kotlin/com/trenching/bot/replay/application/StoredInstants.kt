package com.trenching.bot.replay.application

import java.time.Instant
import java.time.temporal.ChronoUnit

/** Stored timestamps hold microseconds, and the driver would round a finer bound, so bounds are moved onto storable instants. */
object StoredInstants {
    /** The first storable instant at or after [instant], which keeps `>=` and `<` exact. */
    fun ceiling(instant: Instant): Instant {
        val micros = instant.truncatedTo(ChronoUnit.MICROS)
        return if (micros == instant) micros else micros.plus(1, ChronoUnit.MICROS)
    }

    /** The last storable instant at or before [instant], which keeps `<=` exact. */
    fun floor(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MICROS)
}
