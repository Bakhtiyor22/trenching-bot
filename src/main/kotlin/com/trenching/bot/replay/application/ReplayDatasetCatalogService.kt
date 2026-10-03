package com.trenching.bot.replay.application

import com.trenching.bot.common.PageResult
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.config.SimulationClock
import org.springframework.beans.factory.annotation.Qualifier
import com.trenching.bot.replay.domain.*
import com.trenching.bot.replay.infrastructure.fixture.ReplayDatasetLoader
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.time.DateTimeException
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

@Service
class ReplayDatasetCatalogService(
    private val datasets: ReplayDatasetTransaction,
    private val entries: DatasetEntryFactory,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
    json: ObjectMapper,
) : ReplayDatasetCatalog {
    private val log = LoggerFactory.getLogger(ReplayDatasetCatalogService::class.java)
    private val loader = ReplayDatasetLoader(json)

    override fun register(name: String, json: InputStream): DatasetRegistration {
        val label = name.trim()
        if (label.isEmpty() || label.length > MAX_NAME_LENGTH) {
            throw DatasetInvalidException("Dataset name must be 1..$MAX_NAME_LENGTH characters")
        }
        val entry = entries.entry(label, parse(json), clock.instant().truncatedTo(ChronoUnit.MICROS))
        val created = datasets.insertIfAbsent(entry)
        val dataset = if (created) entry.summary else checkNotNull(datasets.find(entry.summary.id)) {
            "Existing replay dataset vanished datasetId=${entry.summary.id}"
        }
        log.info("replay dataset registered datasetId={} created={}", dataset.id, created)
        return DatasetRegistration(dataset, created)
    }

    override fun importSample(sample: SampleDataset): DatasetRegistration {
        val name = when (sample) {
            SampleDataset.WEEKLY_BUY_V1 -> "weekly-buy-v1"
        }
        val resource = checkNotNull(javaClass.getResourceAsStream("$SAMPLE_DIRECTORY$name.json")) { "Missing bundled sample=$sample" }
        return resource.use { register(name, it) }
    }

    override fun page(page: Int, size: Int): PageResult<DatasetSummary> = datasets.page(ReplayPaging.request(page, size))

    override fun find(id: String): DatasetSummary? = datasets.find(id)

    override fun load(id: String): ReplayDataset {
        val snapshot = datasets.snapshot(id) ?: throw DatasetNotFoundException(id)
        return loader.load(snapshot.byteInputStream(Charsets.UTF_8))
    }

    private fun parse(json: InputStream): ReplayDataset = try {
        val bytes = json.readBytes()
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
        } catch (exception: CharacterCodingException) {
            throw IllegalArgumentException("Dataset must be UTF-8 JSON", exception)
        }
        // ASCII NULs in UTF-16/32 without a BOM are valid UTF-8 bytes but not UTF-8 JSON.
        require(bytes.none { it == 0.toByte() }) { "Dataset must be UTF-8 JSON" }
        loader.load(bytes.inputStream())
    } catch (exception: RuntimeException) {
        when (exception) {
            is IllegalArgumentException, is DateTimeException, is ArithmeticException, is JacksonException -> {
                val message = exception.message ?: exception.javaClass.simpleName
                log.warn("replay dataset rejected reason={}", message)
                throw DatasetInvalidException(message, exception)
            }
            else -> throw exception
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 128
        const val SAMPLE_DIRECTORY = "/replay/samples/"
    }
}
