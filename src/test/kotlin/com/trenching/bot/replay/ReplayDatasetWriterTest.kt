package com.trenching.bot.replay

import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class ReplayDatasetWriterTest {
    private val json = JsonMapper.builder().build()
    private val loader = ReplayDatasetLoader(json)
    private val writer = ReplayDatasetWriter(json)
    private fun dataset() = javaClass.getResourceAsStream("/replay/samples/dip-sample-v3.json").use {
        loader.load(requireNotNull(it))
    }

    @Test
    fun `written snapshot loads back to the canonical dataset with the same checksum`() {
        val original = dataset()
        val restored = loader.load(writer.write(original).byteInputStream())
        assertEquals(DatasetFingerprint.checksum(original), DatasetFingerprint.checksum(restored))
        assertEquals(OrderedWalletTransactions.canonical(original.transactions), restored.transactions)
        assertEquals(OrderedMarketData.canonical(original.observations), restored.observations)
    }

    @Test
    fun `reordered and repeated delivery writes the identical snapshot`() {
        val original = dataset()
        val repeated = original.copy(transactions = original.transactions.reversed() + original.transactions,
            observations = original.observations.reversed() + original.observations)
        assertEquals(writer.write(original), writer.write(repeated))
    }

    @Test
    fun `observed decimal scale and absent optional fields survive the round trip`() {
        val original = dataset()
        val first = original.observations.first()
        val input = original.copy(
            transactions = original.transactions.map { it.copy(blockTime = null) },
            observations = listOf(first.copy(price = BigDecimal("10.0500"), liquidityQuote = null)) + original.observations.drop(1),
        )
        val restored = loader.load(writer.write(input).byteInputStream())
        assertEquals("10.0500", restored.observations.single { it.id == first.id }.price.toPlainString())
        assertEquals(null, restored.observations.single { it.id == first.id }.liquidityQuote)
        assertEquals(List(input.transactions.size) { null }, restored.transactions.map { it.blockTime })
    }

    @Test
    fun `each observation's curve is written as raw integer strings`() {
        val original = dataset()
        val first = OrderedMarketData.canonical(original.observations).first()

        val curve = json.readTree(writer.write(original)).path("observations").path(0).path("curve")

        assertEquals(first.curve.reserves.virtualSol.toString(), curve.path("virtualSolReserves").asString())
        assertEquals(first.curve.reserves.virtualToken.toString(), curve.path("virtualTokenReserves").asString())
        assertEquals(first.curve.reserves.realSol.toString(), curve.path("realSolReserves").asString())
        assertEquals(first.curve.reserves.realToken.toString(), curve.path("realTokenReserves").asString())
        assertEquals(first.curve.feeBps.toString(), curve.path("feeBps").asString())
        assertEquals(first.curve.creatorFeeBps.toString(), curve.path("creatorFeeBps").asString())
        assertTrue(curve.path("feeBps").isString)
    }

    @Test
    fun `empty dataset round trips`() {
        val empty = dataset().copy(transactions = emptyList(), observations = emptyList())
        assertEquals(empty, loader.load(writer.write(empty).byteInputStream()))
    }
}
