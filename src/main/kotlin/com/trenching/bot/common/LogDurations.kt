package com.trenching.bot.common

import java.time.Duration

/** Durations as log lines print them: `2d10h31m`, `1h5m`, `35m`, `45s`. A negative one, as a clock stepped back gives, prints `0s`: a log call never throws. */
object LogDurations {
    private const val SECONDS_PER_MINUTE = 60L

    fun compact(value: Duration): String {
        val duration = value.coerceAtLeast(Duration.ZERO)
        if (duration.seconds < SECONDS_PER_MINUTE) return "${duration.seconds}s"
        val days = duration.toDays()
        val hours = duration.toHoursPart()
        val minutes = duration.toMinutesPart()
        return buildString {
            if (days > 0) append(days).append('d')
            if (hours > 0) append(hours).append('h')
            if (minutes > 0) append(minutes).append('m')
        }
    }
}
