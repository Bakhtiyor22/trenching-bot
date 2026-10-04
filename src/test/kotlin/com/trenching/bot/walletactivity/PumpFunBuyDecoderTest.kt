package com.trenching.bot.walletactivity

import com.trenching.bot.common.Base58
import com.trenching.bot.common.Commitment
import com.trenching.bot.common.MarketPair
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.Slot
import com.trenching.bot.common.TokenAmount
import com.trenching.bot.common.TransactionSignature
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.TradeSide
import com.trenching.bot.walletactivity.domain.WalletTransaction
import com.trenching.bot.walletactivity.infrastructure.capture.WalletTransactionEvidence
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoder
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoding
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyRejection
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class PumpFunBuyDecoderTest {
    private val mapper = JsonMapper.builder().build()
    private val wallet = WalletAddress.of("4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9")
    private val decoder = PumpFunBuyDecoder()

    @Test
    fun `decodes the captured Pump fun bonding curve buy into the ADR representation`() {
        val expected = WalletTransaction(
            wallet = wallet,
            signature = TransactionSignature.of(SIGNATURE),
            instructionIndex = 1,
            slot = Slot.of(453004996),
            blockTime = Instant.ofEpochSecond(1791049629),
            pair = MarketPair.of(MintAddress.of(MINT), MintAddress.WRAPPED_SOL),
            baseDelta = TOKEN_AMOUNT,
            quoteDelta = SOL_AMOUNT.negate(),
            baseDecimals = 6,
            quoteDecimals = 9,
            successful = true,
            supportedRoute = true,
            signer = true,
            source = "pumpfun-bonding-curve-buy/v1",
            commitment = Commitment.CONFIRMED,
        )
        assertEquals(expected, decoded(buy()))
    }

    @Test
    fun `decoded buy is accepted unchanged by the existing wallet normalizer`() {
        val outcome = WalletNormalizer().normalize(decoded(buy()), wallet, MintAddress.WRAPPED_SOL)
        val trade = assertIs<NormalizationOutcome.Accepted>(outcome).trade
        assertEquals(TradeSide.BUY, trade.side)
        assertEquals(TokenAmount.of(BigInteger("1238520682656"), 6), trade.baseAmount)
        assertEquals(TokenAmount.of(BigInteger("244444444"), 9), trade.quoteAmount)
        assertEquals("$SIGNATURE:1", trade.id)
    }

    @Test
    fun `failed third party sample is not a signer capture and never decodes as a buy`() {
        val failed = fixture("failed-third-party.json").path("result")
        val selection = WalletHistorySelection(failed.path("transaction").path("signatures").path(0).asString(), failed.path("slot").asLong())
        assertEquals(WalletHistoryOutcome.INVALID_TRANSACTION, WalletTransactionEvidence().classify(failed, selection, wallet.value))
        assertEquals(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE, rejection(failed).outcome)
    }

    @Test
    fun `transactions without a Pump fun buy and buy event are unsupported routes`() {
        val withoutPump = buy().also { inner(it).remove(EVENT); inner(it).remove(BUY) }
        val sell = buy().also { replacePrefix(instruction(it, BUY), "33e685a4017f83ad") }
        val buyExactSolIn = buy().also { replacePrefix(instruction(it, BUY), "38fc74089edfcd5f") }
        val sellEvent = buy().also { rewrite(instruction(it, EVENT)) { bytes -> bytes[IS_BUY] = 0 } }
        mapOf(
            withoutPump to PumpFunBuyRejection.NO_BUY,
            sell to PumpFunBuyRejection.NO_BUY,
            buyExactSolIn to PumpFunBuyRejection.NO_BUY,
            sellEvent to PumpFunBuyRejection.SELL_EVENT,
        ).forEach { (transaction, reason) ->
            assertEquals(reason, rejection(transaction))
            assertEquals(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE, reason.outcome)
        }
    }

    @Test
    fun `a Program data log line without the event instruction is not decoded`() {
        val transaction = buy().also { inner(it).remove(EVENT) }
        assertTrue(transaction.path("meta").path("logMessages").any { it.asString().startsWith("Program data: ") })
        assertEquals(PumpFunBuyRejection.NO_TRADE_EVENT, rejection(transaction))
        assertEquals(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE, PumpFunBuyRejection.NO_TRADE_EVENT.outcome)
    }

    @Test
    fun `conflicting or incomplete buy evidence is ambiguous`() {
        val otherKey = Base58.decode(MINT)
        val cases = mapOf(
            "two buys" to (buy().also { inner(it).add(instruction(it, BUY).deepCopy()) } to PumpFunBuyRejection.MULTIPLE_BUYS),
            "two events" to (buy().also { inner(it).add(instruction(it, EVENT).deepCopy()) } to PumpFunBuyRejection.MULTIPLE_TRADE_EVENTS),
            "event user is not the wallet" to (buy().also { rewrite(instruction(it, EVENT)) { bytes ->
                requireNotNull(otherKey).copyInto(bytes, USER) } } to PumpFunBuyRejection.OTHER_USER),
            "event token amount differs from buy argument" to (buy().also { rewrite(instruction(it, BUY)) { bytes ->
                writeU64(bytes, BUY_TOKEN_AMOUNT, TOKEN_AMOUNT.add(BigInteger.ONE)) } } to PumpFunBuyRejection.AMOUNT_MISMATCH),
            "wallet token balance change differs" to (buy().also { walletBalance(it, "postTokenBalances")
                .put("amount", "30298770204102") } to PumpFunBuyRejection.BALANCE_MISMATCH),
            "pre and post decimals differ" to (buy().also { walletBalance(it, "preTokenBalances")
                .put("decimals", 9) } to PumpFunBuyRejection.DECIMALS_MISMATCH),
            "buy and event under different top-level instructions" to (buy().also { moveEventToTopLevelInstruction(it, 2) }
                to PumpFunBuyRejection.INSTRUCTION_MISMATCH),
            "event data truncated inside the prefix" to (buy().also { rewriteLength(instruction(it, EVENT), TRADE_EVENT_PREFIX - 1) }
                to PumpFunBuyRejection.MALFORMED_EVIDENCE),
            "buy data shorter than its arguments" to (buy().also { rewriteLength(instruction(it, BUY), 23) }
                to PumpFunBuyRejection.MALFORMED_EVIDENCE),
            "buy data is not base58" to (buy().also { instruction(it, BUY).put("data", "0OIl") } to PumpFunBuyRejection.MALFORMED_EVIDENCE),
            "zero token amount" to (buy().also {
                rewrite(instruction(it, BUY)) { bytes -> writeU64(bytes, BUY_TOKEN_AMOUNT, BigInteger.ZERO) }
                rewrite(instruction(it, EVENT)) { bytes -> writeU64(bytes, EVENT_TOKEN_AMOUNT, BigInteger.ZERO) }
            } to PumpFunBuyRejection.ZERO_AMOUNT),
            "zero sol amount" to (buy().also { rewrite(instruction(it, EVENT)) { bytes ->
                writeU64(bytes, EVENT_SOL_AMOUNT, BigInteger.ZERO) } } to PumpFunBuyRejection.ZERO_AMOUNT),
        )
        cases.forEach { (name, case) ->
            val (transaction, reason) = case
            assertEquals(reason, rejection(transaction), name)
            assertEquals(WalletHistoryDecodeOutcome.AMBIGUOUS, reason.outcome, name)
        }
    }

    @Test
    fun `only the trade event prefix is read and trailing event bytes are ignored`() {
        val transaction = buy().also { rewriteLength(instruction(it, EVENT), TRADE_EVENT_PREFIX) }
        assertEquals(decoded(buy()), decoded(transaction))
    }

    @Test
    fun `amounts at the u64 maximum are carried exactly`() {
        val transaction = buy().also {
            rewrite(instruction(it, BUY)) { bytes -> writeU64(bytes, BUY_TOKEN_AMOUNT, U64_MAX) }
            rewrite(instruction(it, EVENT)) { bytes ->
                writeU64(bytes, EVENT_TOKEN_AMOUNT, U64_MAX)
                writeU64(bytes, EVENT_SOL_AMOUNT, U64_MAX)
            }
            walletBalance(it, "preTokenBalances").put("amount", "0")
            walletBalance(it, "postTokenBalances").put("amount", U64_MAX.toString())
        }
        val decoded = decoded(transaction)
        assertEquals(U64_MAX, decoded.baseDelta)
        assertEquals(U64_MAX.negate(), decoded.quoteDelta)
    }

    @Test
    fun `a newly created wallet token account without a pre balance counts from zero`() {
        val transaction = buy().also {
            removeWalletBalance(it, "preTokenBalances")
            walletBalance(it, "postTokenBalances").put("amount", TOKEN_AMOUNT.toString())
        }
        val decoded = decoded(transaction)
        assertEquals(TOKEN_AMOUNT, decoded.baseDelta)
        assertEquals(6, decoded.baseDecimals)
    }

    @Test
    fun `a wallet token account without a post balance is ambiguous`() {
        val transaction = buy().also { removeWalletBalance(it, "postTokenBalances") }
        assertEquals(PumpFunBuyRejection.MISSING_BALANCE, rejection(transaction))
        assertEquals(WalletHistoryDecodeOutcome.AMBIGUOUS, PumpFunBuyRejection.MISSING_BALANCE.outcome)
    }

    @Test
    fun `a top-level Pump fun buy without a router decodes with its own instruction index`() {
        val transaction = buy().also {
            val topLevelBuy = instruction(it, BUY).deepCopy().put("stackHeight", 1)
            (it.path("transaction").path("message").path("instructions") as ArrayNode).set(ROUTER_INDEX, topLevelBuy)
            inner(it).remove(BUY)
        }
        assertEquals(decoded(buy()), decoded(transaction))
    }

    @Test
    fun `a missing block time stays null`() {
        val transaction = buy().also { it.putNull("blockTime") }
        assertNull(decoded(transaction).blockTime)
    }

    private fun decoded(transaction: JsonNode): WalletTransaction =
        assertIs<PumpFunBuyDecoding.Decoded>(decoder.decode(transaction, wallet)).transaction

    private fun rejection(transaction: JsonNode): PumpFunBuyRejection =
        assertIs<PumpFunBuyDecoding.Rejected>(decoder.decode(transaction, wallet)).reason

    private fun buy(): ObjectNode = fixture("pumpfun-buy.json") as ObjectNode

    private fun fixture(name: String): JsonNode =
        javaClass.getResourceAsStream("/walletactivity/decoder/$name").use { mapper.readTree(requireNotNull(it)) }

    private fun inner(transaction: ObjectNode): ArrayNode =
        transaction.path("meta").path("innerInstructions").path(0).path("instructions") as ArrayNode

    private fun instruction(transaction: ObjectNode, index: Int): ObjectNode = inner(transaction).path(index) as ObjectNode

    private fun rewrite(instruction: ObjectNode, change: (ByteArray) -> Unit) {
        val bytes = requireNotNull(Base58.decode(instruction.path("data").asString()))
        change(bytes)
        instruction.put("data", Base58.encode(bytes))
    }

    private fun rewriteLength(instruction: ObjectNode, length: Int) {
        val bytes = requireNotNull(Base58.decode(instruction.path("data").asString()))
        instruction.put("data", Base58.encode(bytes.copyOf(length)))
    }

    private fun replacePrefix(instruction: ObjectNode, discriminatorHex: String) =
        rewrite(instruction) { bytes -> discriminatorHex.chunked(2).forEachIndexed { index, hex -> bytes[index] = hex.toInt(16).toByte() } }

    private fun writeU64(bytes: ByteArray, offset: Int, value: BigInteger) {
        (0 until Long.SIZE_BYTES).forEach { index -> bytes[offset + index] = value.shiftRight(index * Byte.SIZE_BITS).toByte() }
    }

    private fun walletBalance(transaction: ObjectNode, field: String): ObjectNode =
        transaction.path("meta").path(field).first { it.path("owner").asString() == wallet.value }.path("uiTokenAmount") as ObjectNode

    private fun removeWalletBalance(transaction: ObjectNode, field: String) {
        val balances = transaction.path("meta").path(field) as ArrayNode
        balances.remove(balances.indexOfFirst { it.path("owner").asString() == wallet.value })
    }

    private fun moveEventToTopLevelInstruction(transaction: ObjectNode, topLevelIndex: Int) {
        val event = inner(transaction).remove(EVENT)
        val group = mapper.createObjectNode().put("index", topLevelIndex)
        group.putArray("instructions").add(event)
        (transaction.path("meta").path("innerInstructions") as ArrayNode).add(group)
    }

    private companion object {
        const val SIGNATURE = "35Vnpu9DSwADxy77FBQV8seGitMXuQRPB575AKXv1Jhh3NQSuZRKtuppUkiyLkYszxxi9HaKcmNN7fywqJ9R3mNA"
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val ROUTER_INDEX = 1
        const val BUY = 0
        const val EVENT = 7
        const val BUY_TOKEN_AMOUNT = 8
        const val EVENT_SOL_AMOUNT = 48
        const val EVENT_TOKEN_AMOUNT = 56
        const val IS_BUY = 64
        const val USER = 65
        const val TRADE_EVENT_PREFIX = 233
        val TOKEN_AMOUNT = BigInteger("1238520682656")
        val SOL_AMOUNT = BigInteger("244444444")
        val U64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    }
}
