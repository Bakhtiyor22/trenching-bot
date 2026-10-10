package com.trenching.bot.portfolio.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.RecordBatch
import com.trenching.bot.common.RecordBatchMismatchException
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.papertrading.domain.RejectionReason
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.StoredPosition
import com.trenching.bot.portfolio.infrastructure.persistence.PositionRepository
import com.trenching.bot.portfolio.mapper.PositionMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PositionStoreService(
    private val repository: PositionRepository,
    private val orders: PaperOrderStore,
    private val mapper: PositionMapper,
) : PositionStore {
    @Transactional
    override fun record(positions: List<Position>, exitUnavailable: Map<String, RejectionReason>) {
        val runId = positions.firstOrNull()?.runId ?: return
        RecordBatch.requireWithinLimit(RECORDS, runId, positions.size, ReplayLimits.MAX_SCENARIO_RECORDS)
        positions.firstOrNull { it.runId != runId }?.let {
            throw RecordBatchMismatchException(RECORDS, runId, "positionId=${it.id} positionRunId=${it.runId}")
        }
        positions.forEach { position -> position.close?.let { close ->
            DecimalPolicy.requireExactPrice(close.executionPrice)
            listOf(close.grossProceeds, close.fee, close.transactionCost, close.netProceeds, close.realizedPnl).forEach(DecimalPolicy::requireExact)
        } }
        repository.saveAllAndFlush(positions.map { mapper.toEntity(it, exitUnavailable[it.id]) })
    }

    @Transactional(readOnly = true)
    override fun positions(runId: String): List<Position> = storedPositions(runId).map { it.position }

    @Transactional(readOnly = true)
    override fun storedPositions(runId: String): List<StoredPosition> {
        val byOrder = orders.fills(runId).associateBy { it.orderId }
        return repository.findByRunIdOrderByTargetSlotAscIdAsc(runId, PageRequest.of(0, ReplayLimits.MAX_SCENARIO_RECORDS))
            .map { StoredPosition(mapper.toPosition(it, requireNotNull(byOrder[it.fillOrderId]) {
                "Missing fill for position=${it.id}"
            }), it.exitUnavailable) }
    }

    companion object {
        private const val RECORDS = "positions"
    }
}
