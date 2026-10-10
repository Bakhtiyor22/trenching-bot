package com.trenching.bot.console

import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetWriter
import tools.jackson.databind.ObjectMapper

object ConsoleTestData {
    const val SAMPLE_RESOURCE = "/replay/samples/dip-sample-v3.json"
    val UNKNOWN_ID = "0".repeat(64)

    fun sampleText(): String = requireNotNull(javaClass.getResourceAsStream(SAMPLE_RESOURCE)).use { it.readBytes().decodeToString() }

    /**
     * The sample with its rate source relabelled by [tag], so it is a distinct dataset (new checksum)
     * with the sample's economics; [keep] can drop observations to force rejections or open positions.
     */
    fun variant(json: ObjectMapper, tag: String, keep: (MarketObservation) -> Boolean = { true }): String {
        val sample = sampleText().byteInputStream().use { ReplayDatasetLoader(json).load(it) }
        val variant = sample.copy(
            referenceRates = sample.referenceRates.map { it.copy(source = "console-$tag") },
            observations = sample.observations.filter(keep),
        )
        return ReplayDatasetWriter(json).write(variant)
    }
}
