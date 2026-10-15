package com.trenching.bot.console.application

import com.trenching.bot.marketdata.domain.MarketObservation
import java.time.Instant
import java.util.Base64

/** An observation's place in chart order: block time, then slot, then id. Its encoding is the markers' opaque cursor. */
data class ChartPosition(val time: Instant, val slot: Long, val id: String) : Comparable<ChartPosition> {
    override fun compareTo(other: ChartPosition): Int = COMPARATOR.compare(this, other)

    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$VERSION$SEPARATOR${time.epochSecond}$SEPARATOR${time.nano}$SEPARATOR$slot$SEPARATOR$id".toByteArray())

    companion object {
        private const val VERSION = "v1"
        private const val SEPARATOR = ":"
        private const val FIELDS = 5
        private const val NANOS_PER_SECOND = 1_000_000_000
        private val COMPARATOR: Comparator<ChartPosition> = compareBy<ChartPosition>({ it.time }, { it.slot }, { it.id })

        /** Chart order of observations. */
        val ORDER: Comparator<MarketObservation> = compareBy({ it.observedTime }, { it.slot.value }, { it.id })

        fun of(observation: MarketObservation): ChartPosition = ChartPosition(observation.observedTime, observation.slot.value, observation.id)

        /** The position [cursor] encodes, or null when it is not one of this format's cursors. */
        fun decode(cursor: String): ChartPosition? {
            val text = try {
                String(Base64.getUrlDecoder().decode(cursor))
            } catch (_: IllegalArgumentException) {
                return null
            }
            // The id comes last because it may itself contain the separator.
            val parts = text.split(SEPARATOR, limit = FIELDS)
            if (parts.size != FIELDS || parts[0] != VERSION || parts[4].isEmpty()) return null
            val seconds = parts[1].toLongOrNull()?.takeIf { it in Instant.MIN.epochSecond..Instant.MAX.epochSecond } ?: return null
            val nanos = parts[2].toIntOrNull()?.takeIf { it in 0 until NANOS_PER_SECOND } ?: return null
            val slot = parts[3].toLongOrNull() ?: return null
            return ChartPosition(Instant.ofEpochSecond(seconds, nanos.toLong()), slot, parts[4])
        }
    }
}
