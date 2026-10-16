package com.trenching.bot.apiusage.domain

import com.trenching.bot.apiusage.ApiProvider
import com.trenching.bot.apiusage.ApiTask
import java.time.Instant

/** A bucket's identity; [captureScope] is the capture id, or [ApiUsageBuckets.NO_CAPTURE]. */
data class ApiUsageBucketKey(
    val bucketStart: Instant,
    val provider: ApiProvider,
    val operation: String,
    val task: ApiTask,
    val captureScope: String,
)
