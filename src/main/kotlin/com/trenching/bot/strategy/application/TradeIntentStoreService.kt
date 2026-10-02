package com.trenching.bot.strategy.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.RecordBatch
import com.trenching.bot.common.RecordBatchMismatchException
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.strategy.domain.TradeIntent
import com.trenching.bot.strategy.infrastructure.persistence.TradeIntentRepository
import com.trenching.bot.strategy.mapper.TradeIntentMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TradeIntentStoreService(private val repository: TradeIntentRepository, private val mapper: TradeIntentMapper) : TradeIntentStore {
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

    @Transactional(readOnly = true)
    override fun intents(runId: String): List<TradeIntent> =
        repository.findByRunIdOrderByTargetSlotAscIdAsc(runId, PageRequest.of(0, ReplayLimits.MAX_SCENARIO_RECORDS)).map(mapper::toIntent)

    companion object {
        private const val RECORDS = "trade intents"
    }
}
