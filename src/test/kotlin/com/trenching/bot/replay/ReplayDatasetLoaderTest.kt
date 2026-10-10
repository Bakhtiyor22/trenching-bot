package com.trenching.bot.replay

import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.replay.application.DatasetFingerprint
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.support.DipSample
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class ReplayDatasetLoaderTest {
    private val mapper = JsonMapper.builder().build()
    private val loader = ReplayDatasetLoader(mapper)

    @Test
    fun `every bundled observation carries a curve state consistent with curveComplete`() {
        val dataset = DipSample.load()

        assertEquals(ReplayDataset.SCHEMA_VERSION, dataset.schemaVersion)
        assertTrue(dataset.observations.all { it.curve.complete == it.curveComplete })
        assertTrue(dataset.observations.any { it.curveComplete })
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `earlier schema versions are refused`(version: Int) {
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

    /** Loads the sample after [change], with its checksum recomputed so only the change itself can fail the load. */
    private fun load(change: (ObjectNode) -> Unit): ReplayDataset {
        val root = mapper.readTree(DipSample.json()) as ObjectNode
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

    private fun observation(root: ObjectNode): ObjectNode =
        root.path("observations").single { it.path("id").asString() == FIRST_OBSERVATION } as ObjectNode

    private fun curve(root: ObjectNode): ObjectNode = observation(root).path("curve") as ObjectNode

    private companion object {
        const val FIRST_OBSERVATION = "fixture:TP:1000:0"
        const val CURVE_STATE_INVALID = "CURVE_STATE_INVALID"
        const val CURVE_STATE_INCONSISTENT = "CURVE_STATE_INCONSISTENT"
        const val CHECKSUM_MISMATCH = "Replay checksum mismatch computed="
    }
}
