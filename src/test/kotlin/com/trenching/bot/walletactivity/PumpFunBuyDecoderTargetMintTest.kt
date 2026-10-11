package com.trenching.bot.walletactivity

import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoder
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyDecoding
import com.trenching.bot.walletactivity.infrastructure.decoder.PumpFunBuyRejection
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class PumpFunBuyDecoderTargetMintTest {
    private val mapper = JsonMapper.builder().build()
    private val wallet = WalletAddress.of("4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9")
    private val decoder = PumpFunBuyDecoder()

    @Test
    fun `a decoded buy of another mint than the target is an unsupported route`() {
        val rejected = assertIs<PumpFunBuyDecoding.Rejected>(decoder.decode(buy(), wallet, MintAddress.of(OTHER_MINT)))
        assertEquals(PumpFunBuyRejection.OTHER_MINT, rejected.reason)
        assertEquals(WalletHistoryDecodeOutcome.UNSUPPORTED_ROUTE, rejected.reason.outcome)
        assertEquals(setOf("BUY"), rejected.instructions)
    }

    @Test
    fun `a buy of the target mint decodes exactly as without a target`() {
        assertEquals(decoder.decode(buy(), wallet), decoder.decode(buy(), wallet, MintAddress.of(MINT)))
    }

    @Test
    fun `without a target mint any decoded buy is kept`() {
        assertIs<PumpFunBuyDecoding.Decoded>(decoder.decode(buy(), wallet))
    }

    @Test
    fun `a buy that would not decode keeps its own rejection whatever the target`() {
        val withoutBalances = (buy() as ObjectNode).also { it.putNull("meta") }
        assertIs<PumpFunBuyDecoding.Rejected>(decoder.decode(withoutBalances, wallet))
        assertEquals(decoder.decode(withoutBalances, wallet), decoder.decode(withoutBalances, wallet, MintAddress.of(OTHER_MINT)))
    }

    private fun buy(): JsonNode =
        javaClass.getResourceAsStream("/walletactivity/decoder/pumpfun-buy.json").use { mapper.readTree(requireNotNull(it)) }

    private companion object {
        const val MINT = "5bYUihkPWGph9BLyG4uUbNV634UTwjf3Ly5JEsnokbDs"
        const val OTHER_MINT = "hoSDHBQR236CoSLptRpDKPSU1JbcG2Ry52X1iq6pump"
    }
}
