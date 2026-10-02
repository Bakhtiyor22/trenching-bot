package com.trenching.bot.replay.application

import com.trenching.bot.replay.domain.*
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import com.trenching.bot.replay.mapper.SimulationRunMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.walletactivity.domain.OrderedWalletTransactions
import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.marketdata.domain.OrderedMarketData
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.portfolio.PositionStore

@Service
class SimulationRunTransaction(
    private val repository: SimulationRunRepository,
    private val mapper: SimulationRunMapper,
    private val wallets: WalletEventStore,
    private val observations: MarketObservationStore,
    private val intents: TradeIntentStore,
    private val orders: PaperOrderStore,
    private val positions: PositionStore,
) {
    @Transactional
    fun insert(record: SimulationRunRecord, dataset: ReplayDataset, report: SimulationReport): SimulationRunRecord {
        val existing = repository.findOneById(record.id)
        if (existing != null) return mapper.toRecord(existing)
        val stored = repository.saveAndFlush(mapper.toEntity(record))
        wallets.record(record.id, dataset.trackedWallet, OrderedWalletTransactions.canonical(dataset.transactions), report.ingestion)
        observations.record(record.id, OrderedMarketData.canonical(dataset.observations))
        intents.record(report.scenarios.map { it.intent })
        orders.record(record.id, report.scenarios.map { it.intent }, report.scenarios.map { it.outcome })
        positions.record(report.scenarios.mapNotNull { it.position },
            report.scenarios.mapNotNull { scenario -> scenario.exitUnavailable?.let { scenario.intent.id to it } }.toMap())
        return mapper.toRecord(stored)
    }

    @Transactional(readOnly = true)
    fun find(id: String): SimulationRunRecord? = repository.findOneById(id)?.let(mapper::toRecord)
}
