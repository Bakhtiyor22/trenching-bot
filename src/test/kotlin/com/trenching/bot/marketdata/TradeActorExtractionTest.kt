package com.trenching.bot.marketdata

import com.trenching.bot.common.Base58
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.marketdata.infrastructure.extraction.PumpFunTradeObservationExtractor
import com.trenching.bot.marketdata.infrastructure.extraction.TradeExtraction
import com.trenching.bot.protocol.pumpfun.PumpFunInstruction
import com.trenching.bot.protocol.pumpfun.PumpFunInstructionReader
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** An observation's trade is the decoded `TradeEvent`: its `user` as actor, its `isBuy` side and its two raw amounts. */
class TradeActorExtractionTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `the observation's trade is the decoded event's user, side and amounts`() {
        val transaction = fixture()
        val event = events(transaction).single()

        val trade = assertNotNull(observations(transaction).single().trade)

        assertEquals(event.user.value, trade.trader)
        assertEquals(TradeSide.BUY, trade.side)
        assertEquals(event.solAmount, trade.solLamports)
        assertEquals(event.tokenAmount, trade.tokenRaw)
    }

    @Test
    fun `the actor is the event's user, not the fee payer that routed it`() {
        val transaction = fixture()
        val feePayer = transaction.path("transaction").path("message").path("accountKeys").path(0).path("pubkey").asString()
        assertEquals(feePayer, events(transaction).single().user.value, "the captured trade was signed by its own user")
        rewriteEvent(tradeEvents(transaction).single()) { writeKey(it, OTHER_TRADER) }

        val trade = assertNotNull(observations(transaction).single().trade)

        assertEquals(OTHER_TRADER, trade.trader)
        assertNotEquals(feePayer, trade.trader)
    }

    @Test
    fun `a sell event gives a SELL trade`() {
        val transaction = fixture()
        rewriteEvent(tradeEvents(transaction).single()) { it[PumpFunLayout.EVENT_IS_BUY_OFFSET] = 0 }

        assertEquals(TradeSide.SELL, assertNotNull(observations(transaction).single().trade).side)
    }

    @Test
    fun `two trade events of one transaction give two observations, each with its own actor`() {
        val transaction = fixture()
        val first = tradeEvents(transaction).single()
        val second = first.deepCopy()
        rewriteEvent(second) { bytes -> bytes[PumpFunLayout.EVENT_IS_BUY_OFFSET] = 0; writeKey(bytes, OTHER_TRADER) }
        (innerInstructions(transaction) as ArrayNode).add(second)

        val observations = observations(transaction)

        assertEquals(2, observations.size)
        assertEquals(setOf(events(fixture()).single().user.value, OTHER_TRADER), observations.map { assertNotNull(it.trade).trader }.toSet())
        assertEquals(setOf(TradeSide.BUY, TradeSide.SELL), observations.map { assertNotNull(it.trade).side }.toSet())
    }

    @Test
    fun `the observation's identity and source are unchanged by carrying a trade`() {
        val observation = observations(fixture()).single()

        assertEquals(PumpFunLayout.TRADE_SOURCE, observation.source)
        assertEquals("pumpfun-bonding-curve-trade/v1", observation.source)
        assertEquals("pumpfun-bonding-curve-trade/v1:${observation.sourceEventId}:${observation.pair.base.value}", observation.id)
    }

    private fun events(transaction: JsonNode): List<PumpFunInstruction.TradeEvent> =
        PumpFunInstructionReader.read(transaction).filterIsInstance<PumpFunInstruction.TradeEvent>()

    private fun observations(transaction: JsonNode): List<MarketObservation> =
        PumpFunTradeObservationExtractor().extract(transaction,
            TransactionSignature.of(transaction.path("transaction").path("signatures").path(0).asString()),
            Slot.of(transaction.path("slot").asLong())) { _, _ -> true }
            .map { (it as TradeExtraction.Observed).observation }

    private fun rewriteEvent(event: ObjectNode, change: (ByteArray) -> Unit) {
        val bytes = requireNotNull(Base58.decode(event.path("data").asString()))
        change(bytes)
        event.put("data", Base58.encode(bytes))
    }

    private fun writeKey(bytes: ByteArray, key: String) =
        requireNotNull(Base58.decode(key)).copyInto(bytes, PumpFunLayout.EVENT_USER_OFFSET)

    private fun innerInstructions(transaction: JsonNode): JsonNode =
        transaction.path("meta").path("innerInstructions").single { group -> group.path("instructions").any(::isTradeEvent) }.path("instructions")

    private fun tradeEvents(transaction: JsonNode): List<ObjectNode> =
        innerInstructions(transaction).filter(::isTradeEvent).map { it as ObjectNode }

    private fun isTradeEvent(instruction: JsonNode): Boolean =
        instruction.path("programId").asString() == PumpFunLayout.PROGRAM_ID &&
            requireNotNull(Base58.decode(instruction.path("data").asString())).take(PumpFunLayout.TRADE_EVENT_PREFIX.size) ==
            PumpFunLayout.TRADE_EVENT_PREFIX.toList()

    private fun fixture(): JsonNode {
        val root = javaClass.getResourceAsStream("/marketdata/pumpfun-trade-453004997.json").use { mapper.readTree(requireNotNull(it)) }
        return if (root.has("result")) root.path("result") else root
    }

    private companion object {
        const val OTHER_TRADER = PumpFunLayout.PROGRAM_ID
    }
}
