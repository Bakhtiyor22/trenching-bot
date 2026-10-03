package com.trenching.bot.papertrading.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.RecordBatch
import com.trenching.bot.common.RecordBatchMismatchException
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.papertrading.infrastructure.persistence.*
import com.trenching.bot.papertrading.mapper.PaperOrderMapper
import com.trenching.bot.strategy.domain.TradeIntent
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaperOrderStoreService(
    private val orders: PaperOrderRepository,
    private val fills: PaperFillRepository,
    private val mapper: PaperOrderMapper,
) : PaperOrderStore {
    @Transactional
    override fun record(runId: String, intents: List<TradeIntent>, outcomes: List<FillOutcome>) {
        RecordBatch.requireWithinLimit(RECORDS, runId, intents.size, ReplayLimits.MAX_SCENARIO_RECORDS)
        if (intents.size != outcomes.size) {
            throw RecordBatchMismatchException(RECORDS, runId, "intents=${intents.size} outcomes=${outcomes.size}")
        }
        intents.zip(outcomes).forEach { (intent, outcome) ->
            if (intent.runId != runId || intent.id != outcome.orderId) {
                throw RecordBatchMismatchException(RECORDS, runId,
                    "intentId=${intent.id} intentRunId=${intent.runId} orderId=${outcome.orderId}")
            }
        }
        val filled = outcomes.filterIsInstance<FillOutcome.Filled>().map { it.fill }
        filled.forEach { fill ->
            DecimalPolicy.requireExactPrice(fill.executionPrice)
            listOf(fill.quantity, fill.fee, fill.costBasis, fill.priorityFee).forEach(DecimalPolicy::requireExact)
        }
        orders.saveAllAndFlush(intents.zip(outcomes).map { (intent, outcome) -> mapper.toOrder(runId, intent, outcome) })
        fills.saveAllAndFlush(filled.map { mapper.toEntity(runId, it) })
    }

    @Transactional(readOnly = true)
    override fun fills(runId: String): List<PaperFill> =
        fills.findByRunIdOrderByActualSlotAscOrderIdAsc(runId, PageRequest.of(0, ReplayLimits.MAX_SCENARIO_RECORDS)).map(mapper::toFill)

    @Transactional(readOnly = true)
    override fun outcomes(runId: String): List<FillOutcome> {
        val byOrder = fills(runId).associateBy { it.orderId }
        return orders.findByRunIdOrderByTargetSlotAscIdAsc(runId, PageRequest.of(0, ReplayLimits.MAX_SCENARIO_RECORDS))
            .map { mapper.outcome(it, byOrder[it.id]) }
    }

    @Transactional(readOnly = true)
    override fun rejectionCounts(): Map<RejectionReason, Long> =
        orders.countRejectionsByReason().associate { it.reason to it.count }

    companion object {
        private const val RECORDS = "paper orders"
    }
}
