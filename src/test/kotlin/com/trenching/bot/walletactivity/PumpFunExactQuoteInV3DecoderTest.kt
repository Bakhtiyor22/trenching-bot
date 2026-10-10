package com.trenching.bot.walletactivity

import com.trenching.bot.common.Base58
import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.protocol.pumpfun.CurveReserves
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import com.trenching.bot.walletactivity.domain.WalletTransaction
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoder
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoding
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyRejection
import java.math.BigInteger
import java.time.Instant
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** Decu's `buy_exact_quote_in_v3` buys, routed through FLASHX, as captured on 2026-10-10. */
class PumpFunExactQuoteInV3DecoderTest {
    private val mapper = JsonMapper.builder().build()
    private val wallet = WalletAddress.of("4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9")
    private val decoder = PumpFunBuyDecoder()

    @Test
    fun `decodes a SOL quoted exact quote in buy into the same representation as a legacy buy`() {
        val decoding = assertIs<PumpFunBuyDecoding.Decoded>(decoder.decode(v3Buy(), wallet))

        assertEquals(WalletTransaction(
            wallet = wallet,
            signature = TransactionSignature.of(SIGNATURE),
            instructionIndex = ROUTER_INDEX,
            slot = Slot.of(455091775),
            blockTime = Instant.ofEpochSecond(1791599627),
            pair = MarketPair.of(MintAddress.of(MINT), MintAddress.WRAPPED_SOL),
            baseDelta = TOKEN_AMOUNT,
            quoteDelta = SOL_AMOUNT.negate(),
            baseDecimals = 6,
            quoteDecimals = 9,
            successful = true,
            supportedRoute = true,
            signer = true,
            source = PumpFunLayout.BUY_SOURCE,
            commitment = Commitment.CONFIRMED,
        ), decoding.transaction)
        assertEquals(PumpFunBuyFacts(
            signature = TransactionSignature.of(SIGNATURE),
            slot = Slot.of(455091775),
            mint = MintAddress.of(MINT),
            solAmount = SOL_AMOUNT,
            tokenAmount = TOKEN_AMOUNT,
            feeLamports = BigInteger("2322223"),
            creatorFeeLamports = BigInteger("733334"),
            feeBps = BigInteger("95"),
            creatorFeeBps = BigInteger("30"),
            postReserves = CurveReserves(BigInteger("62628603653"), BigInteger("513982408672964"),
                BigInteger("32628603653"), BigInteger("234082408672964")),
            transactionFeeLamports = BigInteger("55000"),
            walletLamportDelta = BigInteger("-250105000"),
            instruction = PumpFunBuyInstruction.BUY_EXACT_QUOTE_IN_V3,
        ), decoding.facts)
    }

    @Test
    fun `a spendable amount other than the curve payment plus both fees is an amount mismatch`() {
        val transaction = v3Buy()
        rewrite(buyInstruction(transaction)) { writeU64(it, SPENDABLE_OFFSET, SPENDABLE.add(BigInteger.ONE)) }

        assertEquals(PumpFunBuyRejection.AMOUNT_MISMATCH, rejection(transaction).reason)
    }

    @Test
    fun `a minimum output above the tokens received is an amount mismatch`() {
        val transaction = v3Buy()
        rewrite(buyInstruction(transaction)) { writeU64(it, MIN_OUT_OFFSET, TOKEN_AMOUNT.add(BigInteger.ONE)) }

        assertEquals(PumpFunBuyRejection.AMOUNT_MISMATCH, rejection(transaction).reason)
    }

    @Test
    fun `data too short for both arguments is malformed evidence`() {
        val transaction = v3Buy()
        val instruction = buyInstruction(transaction)
        instruction.put("data", Base58.encode(Base58.decode(instruction.path("data").asString())!!.copyOf(PumpFunLayout.BUY_EXACT_QUOTE_IN_V3_DATA_BYTES - 1)))

        val rejected = rejection(transaction)
        assertEquals(PumpFunBuyRejection.MALFORMED_EVIDENCE, rejected.reason)
        assertEquals(WalletHistoryDecodeOutcome.AMBIGUOUS, rejected.reason.outcome)
    }

    @Test
    fun `a legacy buy and an exact quote in buy together are multiple buys`() {
        val transaction = v3Buy()
        val extra = buyInstruction(transaction).deepCopy()
        rewrite(extra) { legacy -> HexFormat.of().parseHex("66063d1201daebea").copyInto(legacy) }
        inner(transaction).add(extra)

        assertEquals(PumpFunBuyRejection.MULTIPLE_BUYS, rejection(transaction).reason)
    }

    @Test
    fun `a curve quoted in another token is an unsupported route, not an ambiguous one`() {
        val rejected = rejection(fixture("pumpfun-buy-exact-quote-in-v3-non-sol.json"))

        assertEquals(PumpFunBuyRejection.NON_SOL_QUOTE, rejected.reason)
        assertEquals(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE, rejected.reason.outcome)
        assertEquals(setOf("BUY_EXACT_QUOTE_IN_V3"), rejected.instructions)
    }

    @Test
    fun `a zero SOL amount on a SOL curve stays an ambiguous zero amount`() {
        val transaction = v3Buy()
        rewrite(eventInstruction(transaction)) { writeU64(it, EVENT_SOL_AMOUNT, BigInteger.ZERO) }
        rewrite(buyInstruction(transaction)) { writeU64(it, SPENDABLE_OFFSET, BigInteger("3055557")) }

        assertEquals(PumpFunBuyRejection.ZERO_AMOUNT, rejection(transaction).reason)
    }

    @Test
    fun `a sell v3 has no buy and is named in the rejection`() {
        val rejected = rejection(fixture("pumpfun-sell-v3.json"))

        assertEquals(PumpFunBuyRejection.NO_BUY, rejected.reason)
        assertEquals(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE, rejected.reason.outcome)
        assertEquals(setOf("SELL_V3"), rejected.instructions)
    }

    @Test
    fun `another user's exact quote in buy is rejected as for a legacy buy`() {
        val transaction = v3Buy()
        rewrite(eventInstruction(transaction)) { MintAddress.WRAPPED_SOL.value.let(Base58::decode)!!.copyInto(it, EVENT_USER) }

        assertEquals(PumpFunBuyRejection.OTHER_USER, rejection(transaction).reason)
    }

    @Test
    fun `a transaction without any Pump fun instruction is marked as such`() {
        val transaction = v3Buy()
        (transaction.path("meta") as ObjectNode).putArray("innerInstructions")

        val rejected = rejection(transaction)
        assertEquals(PumpFunBuyRejection.NO_BUY, rejected.reason)
        assertTrue("NO_PUMPFUN" in rejected.instructions)
    }

    private fun rejection(transaction: JsonNode): PumpFunBuyDecoding.Rejected =
        assertIs<PumpFunBuyDecoding.Rejected>(decoder.decode(transaction, wallet))

    private fun v3Buy(): ObjectNode = fixture("pumpfun-buy-exact-quote-in-v3.json") as ObjectNode

    private fun fixture(name: String): JsonNode =
        javaClass.getResourceAsStream("/walletactivity/decoder/$name").use { mapper.readTree(requireNotNull(it)) }

    private fun inner(transaction: ObjectNode): ArrayNode =
        transaction.path("meta").path("innerInstructions").single { it.path("index").asInt() == ROUTER_INDEX }.path("instructions") as ArrayNode

    private fun buyInstruction(transaction: ObjectNode): ObjectNode = inner(transaction).path(BUY_INNER_INDEX) as ObjectNode

    private fun eventInstruction(transaction: ObjectNode): ObjectNode = inner(transaction).path(EVENT_INNER_INDEX) as ObjectNode

    private fun rewrite(instruction: ObjectNode, change: (ByteArray) -> Unit) {
        val bytes = requireNotNull(Base58.decode(instruction.path("data").asString()))
        change(bytes)
        instruction.put("data", Base58.encode(bytes))
    }

    private fun writeU64(bytes: ByteArray, offset: Int, value: BigInteger) {
        val little = value.toByteArray().reversedArray().copyOf(PumpFunLayout.U64_BYTES)
        little.copyInto(bytes, offset)
    }

    private companion object {
        const val SIGNATURE = "26jumCkdDKmagU9CTARuecYGp2zofCQm5kPWA1sXijWMtLMHB5tZJo2Vn478zRmysZ81izXzM9YAX3qevYAatJWq"
        const val MINT = "6LChGSaKeBUoZGkiNc8z3ZB2rttv7pg1XBPaQJ2rpump"
        const val ROUTER_INDEX = 1
        const val BUY_INNER_INDEX = 0
        const val EVENT_INNER_INDEX = 3
        const val SPENDABLE_OFFSET = 8
        const val MIN_OUT_OFFSET = 16
        const val EVENT_SOL_AMOUNT = 48
        const val EVENT_USER = 65
        val SOL_AMOUNT = BigInteger("244444443")
        val TOKEN_AMOUNT = BigInteger("2013975096834")
        val SPENDABLE = BigInteger("247500000")
    }
}
