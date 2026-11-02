package com.trenching.bot.strategy.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.RecordBatch
import com.trenching.bot.common.RecordBatchMismatchException
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.strategy.domain.TradeIntent
import com.trenching.bot.strategy.infrastructure.persistence.TradeIntentEntity
import com.trenching.bot.strategy.infrastructure.persistence.TradeIntentRepository
import com.trenching.bot.strategy.mapper.TradeIntentMapper
import com.trenching.bot.walletactivity.WalletEventStore
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TradeIntentStoreService(
    private val repository: TradeIntentRepository,
    private val mapper: TradeIntentMapper,
    private val walletEvents: WalletEventStore,
) : TradeIntentStore {
    @Transactional
    override fun record(intents: List<TradeIntent>) {
        val runId = intents.firstOrNull()?.runId ?: return
        RecordBatch.requireWithinLimit(RECORDS, runId, intents.size, ReplayLimits.MAX_SCENARIO_RECORDS)
        intents.firstOrNull { it.runId != runId }?.let {
            throw RecordBatchMismatchException(RECORDS, runId, "intentId=${it.id} intentRunId=${it.runId}")
        }
        intents.forEach { DecimalPolicy.requireExact(it.notional) }
        repository.saveAllAndFlush(intents.map(mapper::toEntity))
    }

    /**
     * A row stored before engine dip-paper-v5 has no wallet column value; it reads the wallet of the run's accepted trade it
     * was made from, which is the buy's wallet by construction. The run's trades are read only when such a row exists.
     */
    @Transactional(readOnly = true)
    override fun intents(runId: String): List<TradeIntent> {
        val entities = repository.findByRunIdOrderByTargetSlotAscIdAsc(runId, PageRequest.of(0, ReplayLimits.MAX_SCENARIO_RECORDS))
        val sourceWallets = if (entities.any { it.wallet == null }) {
            walletEvents.acceptedTrades(runId).associate { it.id to it.wallet }
        } else {
            emptyMap()
        }
        return entities.map { mapper.toIntent(it, buyWallet(it, sourceWallets)) }
    }

    private fun buyWallet(entity: TradeIntentEntity, sourceWallets: Map<String, WalletAddress>): WalletAddress =
        entity.wallet?.let(WalletAddress::of) ?: checkNotNull(sourceWallets[entity.sourceEventId]) {
            "Stored intent without a wallet or its source trade runId=${entity.runId} intentId=${entity.id} sourceEventId=${entity.sourceEventId}"
        }

    companion object {
        private const val RECORDS = "trade intents"
    }
}
