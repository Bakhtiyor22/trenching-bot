package com.trenching.bot.console.domain

import com.trenching.bot.common.MintAddress
import com.trenching.bot.strategy.domain.SimulationConfig
import java.io.StringReader
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.ScalarNode

/** Fails when the agreed spec's engine config drifts from the preset; the backend itself never reads `docs/`. */
class AgreedStrategyPresetTest {
    private val preset = AgreedStrategyPreset.PRESET
    private val specText = Files.readString(Path.of(AgreedStrategyPreset.SOURCE.document))
    private val spec: Map<String, String> = engineConfig(specText)

    @Test
    fun `the spec's levels, size, market cap floor and priority fee are the preset's values`() {
        assertEquals(spec.getValue("dipBps").toInt(), preset.config.dipBps)
        assertEquals(spec.getValue("takeProfitBps").toInt(), preset.config.takeProfitBps)
        assertEquals(spec.getValue("stopLossBps").toInt(), preset.config.stopLossBps)
        assertSameDecimal(spec.getValue("notionalSol"), preset.config.notional)
        assertSameDecimal(spec.getValue("minReferenceMcapUsdExclusive"), preset.config.minMarketCapUsd)
        assertSameDecimal(spec.getValue("entryPriorityFeeSol"), preset.config.transactionCostSol)
    }

    @Test
    fun `the preset names the spec it was recovered from`() {
        assertEquals(spec.getValue("strategyName"), preset.source.strategy)
        assertEquals(spec.getValue("strategyVersion").toInt(), preset.source.version)
        assertEquals(LocalDate.parse(Regex("Last updated: (\\d{4}-\\d{2}-\\d{2})").find(specText)?.groupValues?.get(1)), preset.source.updated)
        assertEquals("decu-dip-v2-on-dip-entry-v1", preset.id)
        assertEquals("dip-entry v1", preset.engine)
    }

    @Test
    fun `the preset runs on dip-entry v1 and keeps the engine defaults for what the spec does not set`() {
        val mvp = SimulationConfig.mvp()
        assertEquals(SimulationConfig.of(BigDecimal("0.1"), mvp.maxAgeSlots, 2000, 5500, 4000, BigDecimal("10000"), mvp.horizonSlots, 1,
            BigDecimal("0.001"), quoteMint = MintAddress.WRAPPED_SOL), preset.config)
        assertEquals(4500L, preset.config.horizonSlots)
    }

    @Test
    fun `every configuration field has its status and spec reference, with the horizon flagged missing`() {
        assertEquals(listOf(
            PresetField("dipBps", PresetFieldStatus.AGREED, "P6", null),
            PresetField("takeProfitBps", PresetFieldStatus.AGREED, "P9", null),
            PresetField("stopLossBps", PresetFieldStatus.AGREED, "P10", null),
            PresetField("notional", PresetFieldStatus.AGREED, "P12", "CURVE_PAYMENT_FEES_ON_TOP"),
            PresetField("transactionCostSol", PresetFieldStatus.AGREED, "entryPriorityFeeSol", "NETWORK_FEE_NOT_ADDED"),
            PresetField("quoteMint", PresetFieldStatus.AGREED, "P15", null),
            PresetField("minMarketCapUsd", PresetFieldStatus.AGREED, "P5", null),
            PresetField("latencySlots", PresetFieldStatus.ASSUMPTION, null, "REPLAY_LATENCY_ASSUMED"),
            PresetField("horizonSlots", PresetFieldStatus.MISSING, null, "NO_SINGLE_WINDOW_IN_SPEC"),
            PresetField("maxAgeSlots", PresetFieldStatus.ENGINE_DEFAULT, null, null),
        ), preset.fields)
    }

    @Test
    fun `the eight spec rules dip-entry v1 cannot apply are listed`() {
        assertEquals(listOf("P2", "P3", "Section 5", "P7", "P8", "P11", "Section 8", "P13"), preset.unsupportedRules.map { it.specRef })
        assertEquals(preset.unsupportedRules.size, preset.unsupportedRules.map { it.code }.toSet().size)
    }

    @Test
    fun `the spec's post-trade price measure is listed, since dip-entry v1 compares average execution prices`() {
        assertEquals(UnsupportedSpecRule("Section 5", "POST_TRADE_PRICE"), preset.unsupportedRules.single { it.code == "POST_TRADE_PRICE" })
        val section = requireNotNull(Regex("## 5\\. Price measure\\s+(.*?)\\n## ", RegexOption.DOT_MATCHES_ALL)
            .find(specText.replace("\r\n", "\n"))) { "spec Section 5 not found" }.groupValues[1]
        assertTrue("**one measure: the post-trade price, in SOL per token**" in section)
        assertTrue("The average fill price of a trade is never used as a trigger." in section)
    }

    private fun assertSameDecimal(expected: String, actual: BigDecimal) =
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected but was ${actual.toPlainString()}")

    /** The raw scalar text of each top-level key of the spec's engine config, so decimals are compared exactly. */
    private fun engineConfig(markdown: String): Map<String, String> {
        val block = requireNotNull(Regex("### Config \\(engine format\\)\\s+```yaml\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .find(markdown.replace("\r\n", "\n"))) { "engine config block not found in the spec" }.groupValues[1]
        val root = Yaml().compose(StringReader(block)) as MappingNode
        return root.value.mapNotNull { tuple ->
            val key = (tuple.keyNode as ScalarNode).value
            (tuple.valueNode as? ScalarNode)?.let { key to it.value }
        }.toMap()
    }
}
