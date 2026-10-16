package com.trenching.bot.apiusage.domain

import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

object ApiUsageBuckets {
    const val BUCKET_SECONDS = 300
    const val NO_CAPTURE = ""
    private const val ID_SEPARATOR = "|"

    /** The UTC five-minute window [instant] falls in. */
    fun startOf(instant: Instant): Instant =
        Instant.ofEpochSecond(Math.floorDiv(instant.epochSecond, BUCKET_SECONDS.toLong()) * BUCKET_SECONDS)

    fun scopeOf(captureId: String?): String = captureId ?: NO_CAPTURE

    /** SHA-256 hex of the identity tuple. No part can contain the separator, so distinct keys never share an id. */
    fun id(key: ApiUsageBucketKey): String {
        val identity = listOf(key.bucketStart.epochSecond.toString(), key.provider.name, key.operation, key.task.name, key.captureScope)
            .joinToString(ID_SEPARATOR)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)))
    }
}
