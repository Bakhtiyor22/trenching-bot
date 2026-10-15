package com.trenching.bot.replay

import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `rewriting the bundled v3 sample is byte identical to what the v3 writer always produced and adds no trade`() {
        val written = writer.write(dataset())

        assertEquals(V3_REWRITE_SHA256, sha256(written))
        assertTrue(json.readTree(written).path("observations").none { it.has("trade") })
    }

    @Test
    fun `a v3 dataset whose observations carry trades is still written without them`() {
        val original = dataset()
        val withTrades = original.copy(observations = original.observations.mapIndexed { index, it -> it.copy(trade = DipSampleV4.trade(index)) })

        assertEquals(writer.write(original), writer.write(withTrades))
    }

    @Test
    fun `a v4 dataset writes each observation's trade as strings after the curve and loads back equal`() {
        val twin = DipSampleV4.dataset()
        val written = writer.write(twin)
        val first = OrderedMarketData.canonical(twin.observations).first()

        val node = json.readTree(written).path("observations").path(0)
        val trade = node.path("trade")

        assertEquals(4, json.readTree(written).path("schemaVersion").asInt())
        assertEquals(listOf("trader", "side", "solLamports", "tokenRaw"), trade.propertyNames().toList())
        assertEquals(requireNotNull(first.trade).trader, trade.path("trader").asString())
        assertEquals(requireNotNull(first.trade).side.name, trade.path("side").asString())
        assertEquals(requireNotNull(first.trade).solLamports.toString(), trade.path("solLamports").asString())
        assertEquals(requireNotNull(first.trade).tokenRaw.toString(), trade.path("tokenRaw").asString())
        assertEquals("trade", node.propertyNames().toList().last())
        val restored = loader.load(written.byteInputStream())
        assertEquals(OrderedMarketData.canonical(twin.observations), restored.observations)
        assertEquals(written, writer.write(restored))
    }

    @Test
    fun `a v4 dataset with an observation lacking its trade is not written`() {
        val twin = DipSampleV4.dataset()
        val broken = twin.copy(observations = listOf(twin.observations.first().copy(trade = null)) + twin.observations.drop(1))

        val error = assertFailsWith<IllegalArgumentException> { writer.write(broken) }

        assertTrue(error.message.orEmpty().contains("TRADE_FIELDS_MISSING"), error.message)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** SHA-256 of the bundled sample rewritten by the writer before schema v4 existed. */
        const val V3_REWRITE_SHA256 = "7bf14a7870a032e8505548e5028c70cb7c376acf030cc4b574e4489fb7a5ae49"
    }
}
