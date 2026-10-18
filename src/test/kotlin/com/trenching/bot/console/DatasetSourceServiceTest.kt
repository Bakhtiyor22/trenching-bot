package com.trenching.bot.console

import com.trenching.bot.common.PageResult
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.application.DatasetSourceService
import com.trenching.bot.console.domain.CaptureSource
import com.trenching.bot.console.domain.CaptureSourceLookup
import com.trenching.bot.console.domain.DatasetSource
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.ReplayDataset
import com.trenching.bot.replay.domain.SampleDataset
import java.io.InputStream
import java.time.Instant
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class DatasetSourceServiceTest {
    private val summary = DatasetSummary("dataset-1", "Decu case dataset", WalletAddress.of(WALLET), 4, 1, 1, null, null, emptyList(), Instant.EPOCH)
    private val capture = CaptureSource("s1", "Decu · 7mup…pump · 16 Oct 20:13", "case-1")
    private val catalog = SingleDatasetCatalog(summary)
    private val captures = CaptureSourceLookup { datasetId -> capture.takeIf { datasetId == "dataset-1" || datasetId == "deleted-dataset" } }

    @Test
    fun `a run's dataset is named from the catalog with the capture case that exported it`() {
        assertEquals(DatasetSource("dataset-1", "Decu case dataset", capture), DatasetSourceService(catalog, captures).of("dataset-1"))
    }

    @Test
    fun `a dataset that left the catalog has no name but keeps its capture`() {
        assertEquals(DatasetSource("deleted-dataset", null, capture), DatasetSourceService(catalog, captures).of("deleted-dataset"))
    }

    @Test
    fun `a dataset detail is sourced from the summary it already has`() {
        val service = DatasetSourceService(SingleDatasetCatalog(null), captures)

        assertEquals(DatasetSource("dataset-1", "Decu case dataset", capture), service.of(summary))
    }

    @Test
    fun `without the capture console a dataset has no capture`() {
        assertEquals(DatasetSource("dataset-1", "Decu case dataset", null), DatasetSourceService(catalog, CaptureSourceLookup.ABSENT).of("dataset-1"))
    }

    private class SingleDatasetCatalog(private val summary: DatasetSummary?) : ReplayDatasetCatalog {
        override fun find(id: String): DatasetSummary? = summary?.takeIf { it.id == id }
        override fun register(name: String, json: InputStream): DatasetRegistration = error("unused")
        override fun importSample(sample: SampleDataset): DatasetRegistration = error("unused")
        override fun page(page: Int, size: Int): PageResult<DatasetSummary> = error("unused")
        override fun load(id: String): ReplayDataset = error("unused")
        override fun delete(id: String): Boolean = error("unused")
    }

    private companion object {
        const val WALLET = "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9"
    }
}
