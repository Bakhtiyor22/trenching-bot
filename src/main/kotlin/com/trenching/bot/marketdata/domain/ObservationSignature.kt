package com.trenching.bot.marketdata.domain

import com.trenching.bot.common.Base58

object ObservationSignature {
    private const val SOURCE_EVENT_SEPARATOR = ':'
    private const val SIGNATURE_BYTES = 64
    private const val TOP_LEVEL_INDEX_PART = 1

    /** A 64-byte base58 signature never needs more characters than this. */
    private const val MAX_SIGNATURE_LENGTH = 88

    /** The transaction signature that opens the observation's `sourceEventId`, or null when it is a synthetic id. */
    fun of(observation: MarketObservation): String? {
        val prefix = observation.sourceEventId.substringBefore(SOURCE_EVENT_SEPARATOR)
        return prefix.takeIf { it.length <= MAX_SIGNATURE_LENGTH && Base58.decode(it)?.size == SIGNATURE_BYTES }
    }

    /** The top-level instruction index a `signature:topLevel:inner` `sourceEventId` names, or null when it names none. */
    fun topLevelIndex(observation: MarketObservation): Int? =
        observation.sourceEventId.split(SOURCE_EVENT_SEPARATOR).getOrNull(TOP_LEVEL_INDEX_PART)?.toIntOrNull()
}
