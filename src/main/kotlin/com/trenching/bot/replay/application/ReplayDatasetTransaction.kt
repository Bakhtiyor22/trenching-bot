package com.trenching.bot.replay.application

import com.trenching.bot.common.PageResult
import com.trenching.bot.replay.domain.DatasetEntry
import com.trenching.bot.replay.domain.DatasetSummary
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetRepository
import com.trenching.bot.replay.mapper.ReplayDatasetMapper
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReplayDatasetTransaction(
    private val repository: ReplayDatasetRepository,
    private val mapper: ReplayDatasetMapper,
) {
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
}
