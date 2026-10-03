package com.trenching.bot.replay.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.MintAddress
import com.trenching.bot.common.PageResult
import com.trenching.bot.replay.domain.*
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import com.trenching.bot.replay.mapper.SimulationRunMapper
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.portfolio.PositionStore
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class SimulationRunTransaction(
    private val repository: SimulationRunRepository,
    private val mapper: SimulationRunMapper,
    private val datasets: ReplayDatasetTransaction,
    private val wallets: WalletEventStore,
    private val observations: MarketObservationStore,
    private val intents: TradeIntentStore,
    private val orders: PaperOrderStore,
    private val positions: PositionStore,
) {
    @Transactional
    fun insert(record: SimulationRunRecord, dataset: ReplayDataset, report: SimulationReport, datasetEntry: DatasetEntry): SimulationRunRecord =
        insertWithOutcome(record, dataset, report, datasetEntry).record

    @Transactional
    fun insertWithOutcome(record: SimulationRunRecord, dataset: ReplayDataset, report: SimulationReport, datasetEntry: DatasetEntry): RunPersistence {
        datasets.insertIfAbsent(datasetEntry)
        val existing = repository.findOneById(record.id)
        if (existing != null) return RunPersistence(mapper.toRecord(existing), created = false)
        val stored = repository.saveAndFlush(mapper.toEntity(record))
        wallets.record(record.id, dataset.trackedWallet, OrderedWalletTransactions.canonical(dataset.transactions), report.ingestion)
        observations.record(record.id, OrderedMarketData.canonical(dataset.observations))
        intents.record(report.scenarios.map { it.intent })
        orders.record(record.id, report.scenarios.map { it.intent }, report.scenarios.map { it.outcome })
        positions.record(report.scenarios.mapNotNull { it.position },
            report.scenarios.mapNotNull { scenario -> scenario.exitUnavailable?.let { scenario.intent.id to it } }.toMap())
        return RunPersistence(mapper.toRecord(stored), created = true)
    }

    @Transactional(readOnly = true)
    fun find(id: String): SimulationRunRecord? = repository.findOneById(id)?.let(mapper::toRecord)

    @Transactional(readOnly = true)
    fun exists(id: String): Boolean = repository.existsById(id)

    @Transactional(readOnly = true)
    fun summary(id: String): RunSummary? = repository.findSummaryById(id)?.let(mapper::toSummary)

    @Transactional(readOnly = true)
    fun page(datasetId: String?, pageable: Pageable): PageResult<RunSummary> {
        val rows = if (datasetId == null) repository.findAllProjectedBy(pageable)
            else repository.findAllByDatasetChecksum(datasetId, pageable)
        return ReplayPaging.result(rows, mapper::toSummary)
    }

    @Transactional(readOnly = true)
    fun report(id: String): String? = repository.findReportSnapshotById(id)

    @Transactional(readOnly = true)
    fun aggregates(createdSince: Instant): RunAggregates {
        val byQuoteMint = repository.sumByQuoteMint()
        return RunAggregates(
            totalRuns = byQuoteMint.sumOf { it.runCount },
            runsSince = repository.countByCreatedAtGreaterThanEqual(firstStorableAtOrAfter(createdSince)),
            filledCount = byQuoteMint.sumOf { it.filledCount },
            rejectedCount = byQuoteMint.sumOf { it.rejectedCount },
            openCount = byQuoteMint.sumOf { it.openCount },
            closedCount = byQuoteMint.sumOf { it.closedCount },
            realizedPnlByQuoteMint = byQuoteMint.associate {
                MintAddress.of(it.quoteMint) to it.realizedPnl.setScale(DecimalPolicy.SCALE, RoundingMode.UNNECESSARY)
            },
        )
    }

    /** `created_at` holds microseconds; the driver would round a finer bound, so round it up to keep `>=` exact. */
    private fun firstStorableAtOrAfter(instant: Instant): Instant {
        val micros = instant.truncatedTo(ChronoUnit.MICROS)
        return if (micros == instant) micros else micros.plus(1, ChronoUnit.MICROS)
    }
}
