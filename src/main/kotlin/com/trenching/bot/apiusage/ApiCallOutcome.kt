package com.trenching.bot.apiusage

/** [RATE_LIMITED] is an HTTP 429 reply; [CACHE_HIT] is an answer served locally, with no request sent. */
enum class ApiCallOutcome { OK, FAILED, RATE_LIMITED, CACHE_HIT }
