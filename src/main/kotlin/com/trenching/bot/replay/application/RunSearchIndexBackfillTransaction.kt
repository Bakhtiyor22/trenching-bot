package com.trenching.bot.replay.application

import com.trenching.bot.config.SimulationClock
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.domain.DatasetNotFoundException
import com.trenching.bot.replay.domain.RunIndexBackfillPage
import com.trenching.bot.replay.domain.RunSearchIndex
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunIndexSource
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RunSearchIndexBackfillTransaction(
    private val runs: SimulationRunRepository,
    private val catalog: ReplayDatasetCatalog,
    private val snapshots: CopySnapshotIndexReader,
    private val searchIndex: RunSearchIndexWriter,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(RunSearchIndexBackfillTransaction::class.java)

    /**
     * Indexes the next [PAGE_SIZE] unindexed runs after [afterId] in one transaction. The page holds run ids and small columns only:
     * a copy run's snapshot is read when its turn comes and dropped after, and a referenced dataset is parsed once for the whole pass
     * through [references]. A run whose dataset is gone is marked indexed without tokens and logged once. A run whose dataset cannot
     * be read (see [RunIndexReadFailure]) is counted as failed, logged, and marked failed, which keeps it out of later passes; it does
     * not stop the rest of the page.
     */
    @Transactional
    fun indexPage(afterId: String, references: ReferenceIndexCache): RunIndexBackfillPage {
        val sources = runs.findIndexSourcesAfter(afterId, PageRequest.ofSize(PAGE_SIZE))
        val indexes = LinkedHashMap<String, RunSearchIndex?>()
        val unreadable = mutableListOf<String>()
        sources.forEach { source ->
            try {
                indexes[source.id] = index(source, references)
            } catch (exception: RuntimeException) {
                if (!RunIndexReadFailure.isUnreadable(exception)) throw exception
                unreadable += source.id
                log.warn("run search index unreadable runId={} reason={}", source.id, exception.message)
            }
        }
        searchIndex.storeTokens(indexes.mapNotNull { (runId, index) -> index?.let { runId to it } }.toMap())
        val at = clock.instant()
        indexes.forEach { (runId, index) ->
            if (index == null) log.warn("run search index skipped runId={} reason=DATASET_MISSING", runId)
            runs.markIndexed(runId, index?.anchorBlockTime, at)
        }
        unreadable.forEach { runs.markIndexFailed(it, at) }
        return RunIndexBackfillPage(sources.lastOrNull()?.id, indexes.values.count { it != null }, indexes.values.count { it == null }, unreadable.size)
    }

    private fun index(source: SimulationRunIndexSource, references: ReferenceIndexCache): RunSearchIndex? =
        when (source.storage ?: RunStorage.COPY) {
            RunStorage.COPY -> runs.findDatasetSnapshotById(source.id)?.let(snapshots::read)
            RunStorage.DATASET_REFERENCE -> references.indexOf(source.datasetChecksum) { catalogIndex(source.datasetChecksum) }
        }

    private fun catalogIndex(datasetId: String): RunSearchIndex? = try {
        RunSearchIndex.of(catalog.load(datasetId))
    } catch (exception: DatasetNotFoundException) {
        null
    }

    companion object {
        const val PAGE_SIZE = 200
    }
}
