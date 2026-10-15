package com.trenching.bot.marketdata

import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservationQuality
import com.trenching.bot.marketdata.domain.ObservationSignature
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.support.DipSample
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ObservationSignatureTest {
    @Test
    fun `a captured trade's source event id starts with its transaction signature`() {
        assertEquals(SIGNATURE, ObservationSignature.of(observation("$SIGNATURE:1:7")))
    }

    @Test
    fun `a synthetic fixture id is not a signature`() {
        assertNull(ObservationSignature.of(observation("fixture:TP:1000:0")))
    }

    @Test
    fun `the bundled sample has no signatures`() {
        assertEquals(setOf<String?>(null), DipSample.load().observations.map(ObservationSignature::of).toSet())
    }

    @Test
    fun `a prefix that decodes to another length or is not base58 is not a signature`() {
        listOf("1".repeat(63), "1".repeat(65), "0".repeat(64), "", ":1:7", SIGNATURE.take(60)).forEach { prefix ->
            assertNull(ObservationSignature.of(observation("$prefix:1:7")), prefix)
        }
    }

    @Test
    fun `an id without a separator is its own prefix`() {
        assertEquals(SIGNATURE, ObservationSignature.of(observation(SIGNATURE)))
        assertNull(ObservationSignature.of(observation("observation1")))
    }

    private fun observation(sourceEventId: String) = MarketObservation(
        "id", MarketPair.of(MintAddress.of("1".repeat(31) + "2"), MintAddress.WRAPPED_SOL), Slot.of(1), Slot.of(1), Instant.EPOCH,
        BigDecimal("0.0000001"), null, "source", sourceEventId, Commitment.CONFIRMED, ObservationQuality.LAST_TRADE, false,
        CurveState(CurveReserves(BigInteger.TEN, BigInteger.TEN, BigInteger.ONE, BigInteger.ONE), 0, 0))

    private companion object {
        const val SIGNATURE = "2YHkUxxjxax3y2f8efvMgzboRQ6XDMQpqck7pNpBbEawhFhgAzB32Lh7eRX7RhfkvgjARf2kHgQJWz7rZPDnhvxM"
    }
}
