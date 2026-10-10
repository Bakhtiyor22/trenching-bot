package com.trenching.bot.protocol.pumpfun

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class PumpFunInstructionReaderTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `reads the captured inner trade event with its top-level and inner positions`() {
        val expected = PumpFunInstruction.TradeEvent(
            topLevelIndex = ROUTER_INDEX,
            innerIndex = EVENT_INNER_INDEX,
            mint = MintAddress.of(MINT),
            solAmount = BigInteger("244444444"),
            tokenAmount = BigInteger("1238520682656"),
            isBuy = 1,
            user = WalletAddress.of(USER),
            virtualSolReserves = BigInteger("79829770982"),
            virtualTokenReserves = BigInteger("403233026450985"),
            realSolReserves = BigInteger("49829770982"),
            realTokenReserves = BigInteger("123333026450985"),
            feeBasisPoints = BigInteger("95"),
            feeLamports = BigInteger("2322223"),
            creatorFeeBasisPoints = BigInteger("30"),
            creatorFeeLamports = BigInteger("733334"),
        )
        assertEquals(listOf(expected), PumpFunInstructionReader.read(fixture()).filterIsInstance<PumpFunInstruction.TradeEvent>())
    }

    @Test
    fun `a top-level trade event has no inner index`() {
        val transaction = fixture()
        val event = inner(transaction).remove(EVENT_INNER_INDEX)
        val topLevel = transaction.path("transaction").path("message").path("instructions") as ArrayNode
        topLevel.add(event)

        val read = PumpFunInstructionReader.read(transaction).filterIsInstance<PumpFunInstruction.TradeEvent>().single()

        assertEquals(topLevel.size() - 1, read.topLevelIndex)
        assertEquals(null, read.innerIndex)
    }

    private fun fixture(): ObjectNode =
        javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json").use { mapper.readTree(requireNotNull(it)) } as ObjectNode

    private fun inner(transaction: ObjectNode): ArrayNode =
        transaction.path("meta").path("innerInstructions").path(0).path("instructions") as ArrayNode

    private companion object {
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val USER = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
        const val ROUTER_INDEX = 1
        const val EVENT_INNER_INDEX = 7
    }
}
