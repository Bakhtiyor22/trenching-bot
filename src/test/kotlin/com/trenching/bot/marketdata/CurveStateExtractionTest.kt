package com.trenching.bot.marketdata

import com.trenching.bot.common.Base58
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.MarketObservationExclusion
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.infrastructure.extraction.PumpFunTradeObservationExtractor
import com.trenching.bot.marketdata.infrastructure.extraction.TradeExtraction
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** A trade's curve state and `curveComplete` come from its `TradeEvent`: zero real token reserves means the curve completed with it. */
class CurveStateExtractionTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `the captured trade's post-trade reserves and fee basis points become its curve state`() {
        val expected = CurveState(
            CurveReserves(BigInteger("80123104314"), BigInteger("401756777019139"), BigInteger("50123104314"), BigInteger("121856777019139")),
            feeBps = 95,
            creatorFeeBps = 30,
        )

        assertEquals(listOf(expected), observations(fixture()).map { it.curve })
    }

    @Test
    fun `an exact quote in v3 trade event, longer only by its instruction name, yields the same kind of observation`() {
        val transaction = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy-exact-quote-in-v3.json").use { mapper.readTree(requireNotNull(it)) }

        val observation = observations(transaction).single()

        assertEquals(CurveState(CurveReserves(BigInteger("62628603653"), BigInteger("513982408672964"), BigInteger("32628603653"),
            BigInteger("234082408672964")), feeBps = 95, creatorFeeBps = 30), observation.curve)
        assertEquals("pumpfun-bonding-curve-trade/v1:26jumCkdDKmagU9CTARuecYGp2zofCQm5kPWA1sXijWMtLMHB5tZJo2Vn478zRmysZ81izXzM9YAX3qevYAatJWq:1:3:" +
            "6LChGSaKeBUoZGkiNc8z3ZB2rttv7pg1XBPaQJ2rpump", observation.id)
    }

    @Test
    fun `a trade on a curve quoted in another token is excluded, never priced as SOL`() {
        val transaction = javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy-exact-quote-in-v3-non-sol.json").use { mapper.readTree(requireNotNull(it)) }

        assertEquals(listOf<TradeExtraction>(TradeExtraction.Excluded(MarketObservationExclusion.ZERO_AMOUNT)), extract(transaction))
    }

    @Test
    fun `the captured trade leaves reserves on the curve and is not complete`() {
        assertEquals(listOf(false), observations(fixture()).map { it.curveComplete })
    }

    @Test
    fun `the same trade with zero real token reserves completes the curve`() {
        val transaction = fixture()
        rewriteEvent(transaction) { writeU64(it, PumpFunLayout.EVENT_REAL_TOKEN_RESERVES_OFFSET, BigInteger.ZERO) }

        val observation = observations(transaction).single()

        assertEquals(true, observation.curveComplete)
        assertEquals(BigInteger.ZERO, observation.curve.reserves.realToken)
    }

    @ParameterizedTest
    @ValueSource(ints = [PumpFunLayout.EVENT_FEE_BASIS_POINTS_OFFSET, PumpFunLayout.EVENT_CREATOR_FEE_BASIS_POINTS_OFFSET])
    fun `a fee basis point field above 10000 is a malformed event`(offset: Int) {
        val transaction = fixture()
        rewriteEvent(transaction) { writeU64(it, offset, BigInteger.valueOf(10_001)) }

        assertEquals(listOf(TradeExtraction.Excluded(MarketObservationExclusion.MALFORMED_EVENT)), extract(transaction))
    }

    @Test
    fun `a fee basis point field of exactly 10000 is accepted`() {
        val transaction = fixture()
        rewriteEvent(transaction) { writeU64(it, PumpFunLayout.EVENT_FEE_BASIS_POINTS_OFFSET, BigInteger.valueOf(10_000)) }

        assertEquals(listOf(10_000), observations(transaction).map { it.curve.feeBps })
    }

    @Test
    fun `reserves that break the curve's invariants are a malformed event`() {
        val transaction = fixture()
        rewriteEvent(transaction) { writeU64(it, PumpFunLayout.EVENT_VIRTUAL_SOL_RESERVES_OFFSET, BigInteger.ZERO) }

        assertEquals(listOf(TradeExtraction.Excluded(MarketObservationExclusion.MALFORMED_EVENT)), extract(transaction))
    }

    private fun extract(transaction: JsonNode): List<TradeExtraction> =
        PumpFunTradeObservationExtractor().extract(transaction,
            TransactionSignature.of(transaction.path("transaction").path("signatures").path(0).asString()),
            Slot.of(transaction.path("slot").asLong())) { _, _ -> true }

    private fun observations(transaction: JsonNode): List<MarketObservation> =
        extract(transaction).map { (it as TradeExtraction.Observed).observation }

    private fun rewriteEvent(transaction: JsonNode, change: (ByteArray) -> Unit) {
        val event = tradeEvent(transaction)
        val bytes = requireNotNull(Base58.decode(event.path("data").asString()))
        change(bytes)
        event.put("data", Base58.encode(bytes))
    }

    private fun writeU64(bytes: ByteArray, offset: Int, value: BigInteger) {
        (0 until PumpFunLayout.U64_BYTES).forEach { index -> bytes[offset + index] = value.shiftRight(index * Byte.SIZE_BITS).toByte() }
    }

    private fun tradeEvent(transaction: JsonNode): ObjectNode =
        transaction.path("meta").path("innerInstructions").flatMap { it.path("instructions") }
            .single { it.path("programId").asString() == PumpFunLayout.PROGRAM_ID &&
                requireNotNull(Base58.decode(it.path("data").asString())).take(PumpFunLayout.TRADE_EVENT_PREFIX.size) ==
                PumpFunLayout.TRADE_EVENT_PREFIX.toList() } as ObjectNode

    private fun fixture(): JsonNode {
        val root = javaClass.getResourceAsStream("/marketdata/pumpfun-trade-453004997.json").use { mapper.readTree(requireNotNull(it)) }
        return if (root.has("result")) root.path("result") else root
    }
}
