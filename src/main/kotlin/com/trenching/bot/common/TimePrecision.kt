package com.trenching.bot.common

import java.time.Instant

object TimePrecision {
    private const val NANOS_PER_MICROSECOND = 1000

    fun requireMicroseconds(value: Instant) {
        require(value.nano % NANOS_PER_MICROSECOND == 0) { "Timestamp exceeds microsecond precision: $value" }
    }
}
