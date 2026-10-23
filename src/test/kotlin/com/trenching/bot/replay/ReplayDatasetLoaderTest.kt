package com.trenching.bot.replay

import com.trenching.bot.common.Slot
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.domain.TradeSide
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.domain.MarketCoverage
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV4
import com.trenching.bot.support.DipSampleV5
import com.trenching.bot.support.DipSampleV6
import java.math.BigInteger
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class ReplayDatasetLoaderTest {
    private val mapper = JsonMapper.builder().build()
    private val loader = ReplayDatasetLoader(mapper)

    @Test
    fun `every bundled observation carries a curve state consistent with curveComplete`() {
        val dataset = DipSample.load()

        assertEquals(ReplayDataset.SCHEMA_V3, dataset.schemaVersion)
        assertTrue(dataset.observations.all { it.curve.complete == it.curveComplete })
        assertTrue(dataset.observations.any { it.curveComplete })
    }

    @Test
    fun `the bundled v3 sample keeps its pinned checksum and has no trade fields`() {
        val dataset = DipSample.load()

        assertEquals(PINNED_V3_CHECKSUM, DatasetFingerprint.checksum(dataset))
        assertTrue(dataset.observations.all { it.trade == null })
    }

    @Test
    fun `a v3 checksum ignores trades, so v3 meaning cannot change`() {
        val dataset = DipSample.load()
        val withTrades = dataset.copy(observations = dataset.observations.mapIndexed { index, it -> it.copy(trade = DipSampleV4.trade(index)) })

        assertEquals(DatasetFingerprint.checksum(dataset), DatasetFingerprint.checksum(withTrades))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 7])
    fun `unsupported schema versions are refused`(version: Int) {
        val error = refusal { it.put("schemaVersion", version) }

        assertTrue(error.contains("UNSUPPORTED_SCHEMA"), error)
    }

    @Test
    fun `an observation without a curve is refused`() {
        assertTrue(refusal { observation(it).remove("curve") }.contains(CURVE_STATE_INVALID))
    }

    @ParameterizedTest
    @ValueSource(strings = ["virtualSolReserves", "virtualTokenReserves", "realSolReserves", "realTokenReserves", "feeBps", "creatorFeeBps"])
    fun `a curve without one of its fields is refused`(field: String) {
        assertTrue(refusal { curve(it).remove(field) }.contains(CURVE_STATE_INVALID))
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "1.5", "", "1e3", " 5"])
    fun `a curve field that is not a nonnegative raw integer string is refused`(value: String) {
        assertTrue(refusal { curve(it).put("realSolReserves", value) }.contains(CURVE_STATE_INVALID))
    }

    @Test
    fun `a curve field written as a JSON number is refused`() {
        assertTrue(refusal { curve(it).put("feeBps", 95) }.contains(CURVE_STATE_INVALID))
    }

    @Test
    fun `real reserves above virtual ones are refused`() {
        assertTrue(refusal { curve(it).put("realSolReserves", curve(it).path("virtualSolReserves").asString() + "0") }
            .contains(CURVE_STATE_INVALID))
    }

    @Test
    fun `zero virtual reserves are refused`() {
        assertTrue(refusal { curve(it).put("virtualTokenReserves", "0") }.contains(CURVE_STATE_INVALID))
    }

    @Test
    fun `virtual reserves beyond u64 are refused`() {
        assertTrue(refusal { curve(it).put("virtualSolReserves", "18446744073709551616") }.contains(CURVE_STATE_INVALID))
    }

    @ParameterizedTest
    @ValueSource(strings = ["feeBps", "creatorFeeBps"])
    fun `fee basis points above 10000 are refused`(field: String) {
        assertTrue(refusal { curve(it).put(field, "10001") }.contains(CURVE_STATE_INVALID))
    }

    @Test
    fun `curveComplete true with real tokens left on the curve is refused`() {
        assertTrue(refusal { observation(it).put("curveComplete", true) }.contains(CURVE_STATE_INCONSISTENT))
    }

    @Test
    fun `curveComplete false with no real tokens left on the curve is refused`() {
        assertTrue(refusal { curve(it).put("realTokenReserves", "0") }.contains(CURVE_STATE_INCONSISTENT))
    }

    @Test
    fun `the checksum covers every curve field`() {
        val dataset = DipSample.load()
        val first = dataset.observations.first()
        val reserves = first.curve.reserves
        val variants = listOf(
            first.curve.copy(reserves = reserves.copy(virtualSol = reserves.virtualSol + BigInteger.ONE)),
            first.curve.copy(reserves = reserves.copy(virtualToken = reserves.virtualToken + BigInteger.ONE)),
            first.curve.copy(reserves = reserves.copy(realSol = reserves.realSol + BigInteger.ONE)),
            first.curve.copy(reserves = reserves.copy(realToken = reserves.realToken + BigInteger.ONE)),
            first.curve.copy(feeBps = first.curve.feeBps + 1),
            first.curve.copy(creatorFeeBps = first.curve.creatorFeeBps + 1),
        )
        val original = DatasetFingerprint.checksum(dataset)

        val checksums = variants.map { curve: CurveState ->
            DatasetFingerprint.checksum(dataset.copy(observations = listOf(first.copy(curve = curve)) + dataset.observations.drop(1)))
        }

        checksums.forEach { assertNotEquals(original, it) }
        assertEquals(variants.size, checksums.toSet().size)
    }

    @Test
    fun `reserves and fee basis points load from their raw strings`() {
        val dataset = load { root ->
            curve(root).put("virtualSolReserves", "18446744073709551615")
            curve(root).put("virtualTokenReserves", "18446744073709551615")
            curve(root).put("realSolReserves", "0")
            curve(root).put("feeBps", "10000")
            curve(root).put("creatorFeeBps", "0")
        }
        val curve = dataset.observations.single { it.id == FIRST_OBSERVATION }.curve

        assertEquals(BigInteger("18446744073709551615"), curve.reserves.virtualSol)
        assertEquals(BigInteger("18446744073709551615"), curve.reserves.virtualToken)
        assertEquals(BigInteger.ZERO, curve.reserves.realSol)
        assertEquals(10_000, curve.feeBps)
        assertEquals(0, curve.creatorFeeBps)
    }

    @Test
    fun `a v3 observation with a trade key is refused`() {
        assertTrue(refusal { observation(it).putObject("trade").put("trader", DipSampleV4.trade(0).trader) }.contains(TRADE_FIELDS_IN_V3))
    }

    @Test
    fun `a v3 observation with a null trade is refused`() {
        assertTrue(refusal { observation(it).putNull("trade") }.contains(TRADE_FIELDS_IN_V3))
    }

    @Test
    fun `a v4 dataset loads with each observation's trade`() {
        val dataset = loadV4 { }

        assertEquals(ReplayDataset.SCHEMA_V4, dataset.schemaVersion)
        val expected = DipSampleV4.dataset().observations.associate { it.id to it.trade }
        assertEquals(expected, dataset.observations.associate { it.id to it.trade })
    }

    @Test
    fun `loading a v3 and its v4 twin gives the same observations apart from the trade`() {
        val v3 = DipSample.load()
        val v4 = loadV4 { }

        assertEquals(v3.observations.associateBy { it.id }, v4.observations.associate { it.id to it.copy(trade = null) })
        assertEquals(v3.transactions, v4.transactions)
        assertEquals(v3.referenceRates, v4.referenceRates)
        assertNull(v3.observations.first().trade)
    }

    @Test
    fun `a v4 observation without a trade is refused`() {
        assertTrue(refusalV4 { observation(it).remove("trade") }.contains(TRADE_FIELDS_MISSING))
        assertTrue(refusalV4 { observation(it).putNull("trade") }.contains(TRADE_FIELDS_MISSING))
    }

    @ParameterizedTest
    @ValueSource(strings = ["trader", "side", "solLamports", "tokenRaw"])
    fun `a v4 trade without one of its fields is refused`(field: String) {
        assertTrue(refusalV4 { trade(it).remove(field) }.contains(TRADE_FIELDS_INVALID))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "1.5", "", "1e3", " 5", "18446744073709551616", "123456789012345678901"])
    fun `a v4 trade amount that is not a positive raw u64 string is refused`(value: String) {
        assertTrue(refusalV4 { trade(it).put("solLamports", value) }.contains(TRADE_FIELDS_INVALID))
        assertTrue(refusalV4 { trade(it).put("tokenRaw", value) }.contains(TRADE_FIELDS_INVALID))
    }

    @Test
    fun `a v4 trade amount written as a JSON number is refused`() {
        assertTrue(refusalV4 { trade(it).put("solLamports", 5) }.contains(TRADE_FIELDS_INVALID))
    }

    @Test
    fun `the largest u64 amount is accepted`() {
        val dataset = loadV4 { trade(it).put("solLamports", "18446744073709551615") }

        assertEquals(BigInteger("18446744073709551615"), dataset.observations.single { it.id == FIRST_OBSERVATION }.trade?.solLamports)
    }

    @ParameterizedTest
    @ValueSource(strings = ["BUY ", "buy", "HOLD", ""])
    fun `a v4 trade side other than BUY or SELL is refused`(side: String) {
        assertTrue(refusalV4 { trade(it).put("side", side) }.contains(TRADE_FIELDS_INVALID))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "0OIl", "11111111111111111111111111111111111111111111111111", "1111111111111111111111111111111"])
    fun `a v4 trader that is not a base58 32 byte key is refused`(trader: String) {
        assertTrue(refusalV4 { trade(it).put("trader", trader) }.contains(TRADE_FIELDS_INVALID))
    }

    @Test
    fun `the v4 checksum covers every trade field`() {
        val dataset = DipSampleV4.dataset()
        val first = dataset.observations.first()
        val trade = requireNotNull(first.trade)
        val variants = listOf(
            ObservedTrade.of(DipSampleV4.trade(1).trader.takeIf { it != trade.trader } ?: DipSampleV4.trade(2).trader, trade.side, trade.solLamports, trade.tokenRaw),
            ObservedTrade.of(trade.trader, TradeSide.entries.first { it != trade.side }, trade.solLamports, trade.tokenRaw),
            ObservedTrade.of(trade.trader, trade.side, trade.solLamports + BigInteger.ONE, trade.tokenRaw),
            ObservedTrade.of(trade.trader, trade.side, trade.solLamports, trade.tokenRaw + BigInteger.ONE),
        )
        val original = DatasetFingerprint.checksum(dataset)

        val checksums = variants.map { variant ->
            DatasetFingerprint.checksum(dataset.copy(observations = listOf(first.copy(trade = variant)) + dataset.observations.drop(1)))
        }

        checksums.forEach { assertNotEquals(original, it) }
        assertEquals(variants.size, checksums.toSet().size)
    }

    @Test
    fun `the v4 checksum differs from the v3 checksum of the same observations`() {
        assertNotEquals(DatasetFingerprint.checksum(DipSample.load()), DatasetFingerprint.checksum(DipSampleV4.dataset()))
    }

    @Test
    fun `a v4 file whose trade was edited without recomputing the checksum is refused`() {
        val root = mapper.readTree(DipSampleV4.json()) as ObjectNode
        trade(root).put("tokenRaw", "7")

        val error = requireNotNull(assertFailsWith<IllegalArgumentException> { loader.load(mapper.writeValueAsString(root).byteInputStream()) }.message)

        assertTrue(error.startsWith(CHECKSUM_MISMATCH), error)
    }

    @Test
    fun `the v4 twin keeps the checksum it had before schema v5`() {
        assertEquals(PINNED_V4_CHECKSUM, DatasetFingerprint.checksum(DipSampleV4.dataset()))
    }

    @Test
    fun `a v5 dataset loads each pair's coverage`() {
        val dataset = loadV5 { }

        assertEquals(ReplayDataset.SCHEMA_V5, dataset.schemaVersion)
        assertEquals(DipSampleV5.dataset().coverage.toSet(), dataset.coverage.toSet())
        val takeProfit = dataset.coverage.single { it.pair == DipSample.pair(DipSample.TAKE_PROFIT) }
        assertEquals(Slot.of(1001), takeProfit.fromSlot)
        assertEquals(Slot.of(5500), takeProfit.throughSlot)
    }

    @Test
    fun `a v5 observation whose pair has no coverage entry is refused`() {
        val takeProfit = DipSample.mint(DipSample.TAKE_PROFIT).value

        val error = refusalV5 { root ->
            val coverage = root.path("coverage") as ArrayNode
            coverage.remove(coverage.indexOfFirst { it.path("baseMint").asString() == takeProfit })
        }

        assertTrue(error.contains(COVERAGE_MISSING) && error.contains(takeProfit), error)
    }

    @Test
    fun `a v5 dataset without a coverage array is refused`() {
        assertTrue(refusalV5 { it.remove("coverage") }.contains(COVERAGE_MISSING))
        assertTrue(refusalV5 { it.putNull("coverage") }.contains(COVERAGE_INVALID))
    }

    @ParameterizedTest
    @ValueSource(strings = ["baseMint", "quoteMint", "fromSlot", "throughSlot"])
    fun `a coverage entry without one of its fields is refused`(field: String) {
        assertTrue(refusalV5 { coverage(it).remove(field) }.contains(COVERAGE_INVALID))
    }

    @Test
    fun `a malformed coverage entry is refused`() {
        val malformed: List<(ObjectNode) -> Unit> = listOf(
            { it.put("fromSlot", "1001") },
            { it.put("fromSlot", -1) },
            { it.put("throughSlot", 999) },
            { it.put("quoteMint", it.path("baseMint").asString()) },
            { it.put("baseMint", "not-a-mint") },
        )

        malformed.forEach { change -> assertTrue(refusalV5 { change(coverage(it)) }.contains(COVERAGE_INVALID)) }
    }

    @Test
    fun `two coverage entries for one pair are refused`() {
        val error = refusalV5 { root -> (root.path("coverage") as ArrayNode).add(coverage(root).deepCopy()) }

        assertTrue(error.contains(COVERAGE_INVALID), error)
    }

    @Test
    fun `a window decided at its buy covers no slot and still loads`() {
        val dataset = loadV5 { coverage(it).put("throughSlot", 1000) }

        assertEquals(Slot.of(1000), dataset.coverage.single { it.pair == DipSample.pair(DipSample.TAKE_PROFIT) }.throughSlot)
    }

    @Test
    fun `coverage in a v3 or v4 dataset is refused, so their meaning cannot change`() {
        val entry = mapper.readTree(DipSampleV5.json()).path("coverage")

        assertTrue(refusal { it.replace("coverage", entry.deepCopy()) }.contains(COVERAGE_BEFORE_V5))
        assertTrue(refusalV4 { it.replace("coverage", entry.deepCopy()) }.contains(COVERAGE_BEFORE_V5))
    }

    @Test
    fun `the v5 checksum covers every coverage field and differs from the v4 checksum`() {
        val dataset = DipSampleV5.dataset()
        val first = dataset.coverage.first()
        val other = dataset.coverage.last().pair
        val variants = listOf(
            MarketCoverage.of(other, first.fromSlot, first.throughSlot),
            MarketCoverage.of(first.pair, first.fromSlot.plus(1), first.throughSlot),
            MarketCoverage.of(first.pair, first.fromSlot, first.throughSlot.plus(1)),
        )
        val original = DatasetFingerprint.checksum(dataset)

        val checksums = variants.map { DatasetFingerprint.checksum(dataset.copy(coverage = listOf(it) + dataset.coverage.drop(1))) }

        checksums.forEach { assertNotEquals(original, it) }
        assertEquals(variants.size, checksums.toSet().size)
        assertNotEquals(DatasetFingerprint.checksum(DipSampleV4.dataset()), original)
    }

    @Test
    fun `a v5 file whose coverage was edited without recomputing the checksum is refused`() {
        val root = mapper.readTree(DipSampleV5.json()) as ObjectNode
        coverage(root).put("throughSlot", 1006)

        val error = requireNotNull(assertFailsWith<IllegalArgumentException> { loader.load(mapper.writeValueAsString(root).byteInputStream()) }.message)

        assertTrue(error.startsWith(CHECKSUM_MISMATCH), error)
    }

    @Test
    fun `the v5 sample keeps the checksum it had before schema v6`() {
        assertEquals(PINNED_V5_CHECKSUM, DatasetFingerprint.checksum(DipSampleV5.dataset()))
        assertEquals(PINNED_V5_CHECKSUM, loader.load(DipSampleV5.json().byteInputStream()).let(DatasetFingerprint::checksum))
    }

    @Test
    fun `a v6 dataset round-trips each coverage entry's throughTime and omits a missing one`() {
        val timed = DipSample.pair(DipSample.TAKE_PROFIT)
        val written = DipSampleV6.dataset(mapOf(timed to THROUGH_TIME))

        val json = DipSampleV6.json(written)
        val dataset = loader.load(json.byteInputStream())

        assertEquals(ReplayDataset.SCHEMA_V6, dataset.schemaVersion)
        assertEquals(written.coverage.toSet(), dataset.coverage.toSet())
        assertEquals(THROUGH_TIME, dataset.coverage.single { it.pair == timed }.throughTime)
        assertTrue(dataset.coverage.filter { it.pair != timed }.all { it.throughTime == null })
        val entries = mapper.readTree(json).path("coverage")
        assertEquals(1, entries.count { it.has("throughTime") })
        assertEquals(THROUGH_TIME.toString(), entries.single { it.has("throughTime") }.path("throughTime").asString())
        assertEquals(json, DipSampleV6.json(dataset))
    }

    @Test
    fun `a throughTime in a v5 coverage entry is refused, so a v5 file cannot change meaning`() {
        assertTrue(refusalV5 { coverage(it).put("throughTime", THROUGH_TIME.toString()) }.contains(COVERAGE_INVALID))
    }

    @Test
    fun `a malformed v6 throughTime is refused`() {
        listOf<(ObjectNode) -> Unit>(
            { it.put("throughTime", "yesterday") },
            { it.put("throughTime", 1_792_000_000) },
            { it.put("throughTime", "2026-10-22T16:03:48.0000001Z") },
        ).forEach { change -> assertTrue(refusalV6 { change(coverage(it)) }.contains(COVERAGE_INVALID)) }
    }

    @Test
    fun `the v6 checksum covers throughTime and differs from the v5 checksum`() {
        val pair = DipSample.pair(DipSample.TAKE_PROFIT)
        val checksums = listOf(
            DipSampleV6.dataset(),
            DipSampleV6.dataset(mapOf(pair to THROUGH_TIME)),
            DipSampleV6.dataset(mapOf(pair to THROUGH_TIME.plusSeconds(1))),
        ).map(DatasetFingerprint::checksum)

        assertEquals(checksums.size, checksums.toSet().size)
        assertTrue(PINNED_V5_CHECKSUM !in checksums)
    }

    @Test
    fun `a v6 file whose throughTime was edited without recomputing the checksum is refused`() {
        val root = mapper.readTree(DipSampleV6.json(DipSampleV6.dataset(mapOf(DipSample.pair(DipSample.TAKE_PROFIT) to THROUGH_TIME)))) as ObjectNode
        coverage(root).put("throughTime", THROUGH_TIME.plusSeconds(1).toString())

        val error = requireNotNull(assertFailsWith<IllegalArgumentException> { loader.load(mapper.writeValueAsString(root).byteInputStream()) }.message)

        assertTrue(error.startsWith(CHECKSUM_MISMATCH), error)
    }

    /** Loads the sample after [change], with its checksum recomputed so only the change itself can fail the load. */
    private fun load(change: (ObjectNode) -> Unit): ReplayDataset = load(DipSample.json(), change)

    private fun loadV4(change: (ObjectNode) -> Unit): ReplayDataset = load(DipSampleV4.json(), change)

    private fun loadV5(change: (ObjectNode) -> Unit): ReplayDataset = load(DipSampleV5.json(), change)

    private fun load(base: String, change: (ObjectNode) -> Unit): ReplayDataset {
        val root = mapper.readTree(base) as ObjectNode
        change(root)
        val mismatch = try {
            return loader.load(mapper.writeValueAsString(root).byteInputStream())
        } catch (error: IllegalArgumentException) {
            error.message.orEmpty().takeIf { it.startsWith(CHECKSUM_MISMATCH) } ?: throw error
        }
        root.put("checksum", mismatch.removePrefix(CHECKSUM_MISMATCH))
        return loader.load(mapper.writeValueAsString(root).byteInputStream())
    }

    private fun refusal(change: (ObjectNode) -> Unit): String =
        requireNotNull(assertFailsWith<IllegalArgumentException> { load(change) }.message)

    private fun refusalV4(change: (ObjectNode) -> Unit): String =
        requireNotNull(assertFailsWith<IllegalArgumentException> { loadV4(change) }.message)

    private fun refusalV5(change: (ObjectNode) -> Unit): String =
        requireNotNull(assertFailsWith<IllegalArgumentException> { loadV5(change) }.message)

    private fun refusalV6(change: (ObjectNode) -> Unit): String =
        requireNotNull(assertFailsWith<IllegalArgumentException> { load(DipSampleV6.json(), change) }.message)

    /** The take-profit mint's coverage entry. */
    private fun coverage(root: ObjectNode): ObjectNode =
        root.path("coverage").single { it.path("baseMint").asString() == DipSample.mint(DipSample.TAKE_PROFIT).value } as ObjectNode

    private fun trade(root: ObjectNode): ObjectNode = observation(root).path("trade") as ObjectNode

    private fun observation(root: ObjectNode): ObjectNode =
        root.path("observations").single { it.path("id").asString() == FIRST_OBSERVATION } as ObjectNode

    private fun curve(root: ObjectNode): ObjectNode = observation(root).path("curve") as ObjectNode

    private companion object {
        const val FIRST_OBSERVATION = "fixture:TP:1000:0"
        const val CURVE_STATE_INVALID = "CURVE_STATE_INVALID"
        const val CURVE_STATE_INCONSISTENT = "CURVE_STATE_INCONSISTENT"
        const val TRADE_FIELDS_IN_V3 = "TRADE_FIELDS_IN_V3"
        const val TRADE_FIELDS_MISSING = "TRADE_FIELDS_MISSING"
        const val TRADE_FIELDS_INVALID = "TRADE_FIELDS_INVALID"
        const val COVERAGE_MISSING = "COVERAGE_MISSING"
        const val COVERAGE_INVALID = "COVERAGE_INVALID"
        const val COVERAGE_BEFORE_V5 = "COVERAGE_BEFORE_V5"
        const val PINNED_V4_CHECKSUM = "a12a9079ba8e992bea3d9b01be8b50ae195f25ac36bcbec588c08cafdca4126f"
        const val PINNED_V3_CHECKSUM = "06103b0faebf5d3e95468fc250232c333a0e6b665612c977fc76441883eac765"
        const val PINNED_V5_CHECKSUM = "650565f266723d92d6138d64616bf34e0183b89448509a49460dbfc7fd95bac4"
        val THROUGH_TIME: Instant = Instant.parse("2026-10-22T16:03:48Z")
        const val CHECKSUM_MISMATCH = "Replay checksum mismatch computed="
    }
}
