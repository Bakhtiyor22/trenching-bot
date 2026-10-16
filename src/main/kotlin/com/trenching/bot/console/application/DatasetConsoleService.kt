package com.trenching.bot.console.application

import com.trenching.bot.common.PageResult
import com.trenching.bot.console.domain.DatasetDetail
import com.trenching.bot.console.domain.EmptyDatasetUploadException
import com.trenching.bot.console.domain.SampleDatasetNotFoundException
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetInvalidException
import com.trenching.bot.replay.domain.DatasetNotFoundException
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.SampleDataset
import io.micrometer.core.instrument.MeterRegistry
import java.io.InputStream
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Dataset management for the console; validation and identity belong to the replay catalog. */
@Service
class DatasetConsoleService(
    private val catalog: ReplayDatasetCatalog,
    private val meterRegistry: MeterRegistry,
    private val sources: DatasetSourceService,
) {
    private val log = LoggerFactory.getLogger(DatasetConsoleService::class.java)

    /** [name] labels the dataset; without one the uploaded [fileName] is used. */
    fun upload(name: String?, fileName: String?, size: Long, content: InputStream): DatasetRegistration = content.use {
        if (size == 0L) {
            countUpload(ConsoleMetrics.INVALID)
            log.warn("dataset upload rejected reason=empty fileName={}", fileName)
            throw EmptyDatasetUploadException()
        }
        val label = name?.takeIf { it.isNotBlank() } ?: fileName.orEmpty()
        val registration = try {
            catalog.register(label, it)
        } catch (exception: DatasetInvalidException) {
            countUpload(ConsoleMetrics.INVALID)
            throw exception
        }
        countUpload(if (registration.created) ConsoleMetrics.CREATED else ConsoleMetrics.EXISTING)
        registration
    }

    /** [sample] is matched case-insensitively, with `-` standing for `_` (`dip-sample-v3`). */
    fun importSample(sample: String): DatasetRegistration {
        val key = sample.trim().uppercase().replace('-', '_')
        val dataset = SampleDataset.entries.firstOrNull { it.name == key } ?: throw SampleDatasetNotFoundException(sample)
        return catalog.importSample(dataset)
    }

    fun page(page: Int, size: Int): PageResult<DatasetSummary> {
        ConsolePaging.require(page, size)
        return catalog.page(page, size)
    }

    fun find(id: String): DatasetSummary = catalog.find(id) ?: throw DatasetNotFoundException(id)

    fun detail(id: String): DatasetDetail = find(id).let { DatasetDetail(it, sources.of(it.id, it.name)) }

    /** Removes the dataset from the catalog; its runs stay, each with its own snapshot. */
    fun delete(id: String) {
        if (!catalog.delete(id)) throw DatasetNotFoundException(id)
    }

    /** [pair] is `<baseMint>/<quoteMint>`; without it every pair's observations are returned, slot-ordered. */
    fun observations(id: String, pair: String?): List<MarketObservation> {
        val filter = pair?.let(ConsolePairs::parse)
        val observations = OrderedMarketData.canonical(catalog.load(id).observations)
        return if (filter == null) observations else observations.filter { it.pair == filter }
    }

    private fun countUpload(outcome: String) {
        meterRegistry.counter(ConsoleMetrics.DATASET_UPLOADS, ConsoleMetrics.OUTCOME, outcome).increment()
    }
}
