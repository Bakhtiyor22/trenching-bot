package com.trenching.bot.apiusage

import java.time.Duration

/** A report window ending now; [ALL] has no [duration] and starts at the oldest recorded bucket. */
enum class ApiUsagePeriod(val duration: Duration?) {
    LAST_HOUR(Duration.ofHours(1)),
    LAST_24_HOURS(Duration.ofHours(24)),
    LAST_7_DAYS(Duration.ofDays(7)),
    ALL(null),
}
