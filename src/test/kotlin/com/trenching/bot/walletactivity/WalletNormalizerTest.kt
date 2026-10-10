package com.trenching.bot.walletactivity

import com.trenching.bot.common.*
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.walletactivity.application.WalletNormalizer
import com.trenching.bot.walletactivity.domain.*
import java.math.BigInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class WalletNormalizerTest {
    private val dataset = javaClass.getResourceAsStream("/replay/samples/dip-sample-v3.json").use {
        ReplayDatasetLoader(JsonMapper.builder().build()).load(requireNotNull(it))
    }
    private val normalizer = WalletNormalizer()

    @Test
    fun `buy and sell deltas retain signature instruction and raw amounts`() {
        val source = dataset.transactions[0]
        val selling = source.copy(instructionIndex = 1, baseDelta = source.baseDelta.negate(), quoteDelta = source.quoteDelta.negate())
        val buy = (normalizer.normalize(source, dataset.trackedWallet, MintAddress.WRAPPED_SOL) as NormalizationOutcome.Accepted).trade
        val sell = (normalizer.normalize(selling, dataset.trackedWallet, MintAddress.WRAPPED_SOL) as NormalizationOutcome.Accepted).trade
        assertEquals(TradeSide.BUY, buy.side)
        assertEquals(TradeSide.SELL, sell.side)
        assertEquals(BigInteger("1000000000000"), buy.baseAmount.raw)
        assertNotEquals(buy.id, sell.id)
    }

    @Test
    fun `failed unsupported processed and unsigned inputs produce no trade`() {
        val transaction = dataset.transactions.first()
        listOf(
            transaction.copy(successful = false) to IngestionReason.FAILED,
            transaction.copy(supportedRoute = false) to IngestionReason.UNSUPPORTED_ROUTE,
            transaction.copy(pair = MarketPair.of(transaction.pair.base, MintAddress.of("11111111111111111111111111111113")))
                to IngestionReason.UNSUPPORTED_QUOTE,
            transaction.copy(commitment = Commitment.PROCESSED) to IngestionReason.NOT_CONFIRMED,
            transaction.copy(signer = false) to IngestionReason.NOT_SIGNER,
            transaction.copy(quoteDelta = BigInteger.ONE) to IngestionReason.INVALID_DELTAS,
            transaction.copy(wallet = WalletAddress.of("11111111111111111111111111111112")) to IngestionReason.OTHER_WALLET,
        ).forEach { (input, reason) ->
            assertEquals(reason, (normalizer.normalize(input, dataset.trackedWallet, MintAddress.WRAPPED_SOL) as NormalizationOutcome.Ignored).reason)
        }
    }

    @Test
    fun `malformed amounts and instruction indices fail explicitly`() {
        val transaction = dataset.transactions.first()
        assertFailsWith<IllegalArgumentException> { normalizer.normalize(transaction.copy(instructionIndex = -1), dataset.trackedWallet, MintAddress.WRAPPED_SOL) }
        assertFailsWith<IllegalArgumentException> { normalizer.normalize(transaction.copy(baseDelta = BigInteger.ONE.shiftLeft(65)), dataset.trackedWallet, MintAddress.WRAPPED_SOL) }
    }
}
