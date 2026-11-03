package com.trenching.bot.replay.application

import com.trenching.bot.common.PageResult
import com.trenching.bot.replay.domain.DatasetEntry
import com.trenching.bot.replay.domain.DatasetInUseException
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import com.trenching.bot.replay.mapper.ReplayDatasetMapper
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReplayDatasetTransaction(
    private val repository: ReplayDatasetRepository,
    private val runs: SimulationRunRepository,
    private val mapper: ReplayDatasetMapper,
) {
    private val log = LoggerFactory.getLogger(ReplayDatasetTransaction::class.java)

    /** True when this call stored the dataset; joins the caller's transaction when there is one. */
    @Transactional
    fun insertIfAbsent(entry: DatasetEntry): Boolean {
        val dataset = mapper.toEntity(entry)
        return repository.insertIfAbsent(dataset.id, dataset.name, dataset.trackedWallet, dataset.schemaVersion,
            dataset.transactionCount, dataset.observationCount, dataset.firstSlot, dataset.lastSlot, dataset.pairs,
            dataset.snapshot, dataset.createdAt) == 1
    }

    @Transactional(readOnly = true)
    fun find(id: String): DatasetSummary? = repository.findSummaryById(id)?.let(mapper::toSummary)

    @Transactional(readOnly = true)
    fun page(pageable: Pageable): PageResult<DatasetSummary> =
        ReplayPaging.result(repository.findAllProjectedBy(pageable), mapper::toSummary)

    @Transactional(readOnly = true)
    fun snapshot(id: String): String? = repository.findSnapshotById(id)

    /**
     * Holds the dataset until the caller's transaction ends, so a concurrent [delete] waits for it and then counts the
     * caller's run. False when the dataset is gone.
     */
    @Transactional
    fun holdForReference(id: String): Boolean = repository.findIdLockedForShare(id) != null

    /**
     * True when this call removed the dataset. Refused, removing nothing, while dataset-referenced runs read it. The row is
     * deleted before the runs are counted, so the delete first waits for any run transaction holding it.
     */
    @Transactional
    fun delete(id: String): Boolean {
        if (repository.deleteDataset(id) == 0) return false
        val referenceRuns = runs.countByDatasetChecksumAndStorage(id, RunStorage.DATASET_REFERENCE)
        if (referenceRuns > 0) {
            log.warn("replay dataset delete refused datasetId={} referenceRuns={}", id, referenceRuns)
            throw DatasetInUseException(id, referenceRuns)
        }
        return true
    }
}
