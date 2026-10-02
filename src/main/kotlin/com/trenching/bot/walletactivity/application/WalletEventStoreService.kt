package com.trenching.bot.walletactivity.application

import com.trenching.bot.common.*
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.walletactivity.domain.*
import com.trenching.bot.walletactivity.infrastructure.persistence.WalletEventRepository
import com.trenching.bot.walletactivity.mapper.WalletEventMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WalletEventStoreService(private val repository: WalletEventRepository, private val mapper: WalletEventMapper) : WalletEventStore {
    @Transactional
    override fun record(runId: String, trackedWallet: WalletAddress, transactions: List<WalletTransaction>, outcomes: List<NormalizationOutcome>) {
        RecordBatch.requireWithinLimit(RECORDS, runId, transactions.size, ReplayLimits.MAX_INPUT_RECORDS)
        val byId = outcomes.associateBy { when (it) {
            is NormalizationOutcome.Accepted -> it.trade.id
            is NormalizationOutcome.Ignored -> it.sourceEventId
        } }
        val entities = transactions.map { transaction ->
            transaction.blockTime?.let(TimePrecision::requireMicroseconds)
            TokenAmount.of(transaction.baseDelta.abs(), transaction.baseDecimals)
            TokenAmount.of(transaction.quoteDelta.abs(), transaction.quoteDecimals)
            val eventId = WalletEventIdentity.of(transaction.signature, transaction.instructionIndex)
            val outcome = byId[eventId] ?: throw RecordBatchMismatchException(RECORDS, runId, "missingOutcomeFor=$eventId")
            mapper.toEntity(runId, trackedWallet, transaction, outcome)
        }
        repository.saveAllAndFlush(entities)
    }

    @Transactional(readOnly = true)
    override fun transactions(runId: String): List<WalletTransaction> = rows(runId).map(mapper::toTransaction)

    @Transactional(readOnly = true)
    override fun acceptedTrades(runId: String): List<WalletTradeObserved> = rows(runId).filter { it.side != null }.map(mapper::toTrade)

    @Transactional(readOnly = true)
    override fun ignored(runId: String): List<NormalizationOutcome.Ignored> = rows(runId).filter { it.ignoredReason != null }.map(mapper::toIgnored)

    private fun rows(runId: String) =
        repository.findByRunIdOrderBySlotAscSignatureAscInstructionIndexAsc(runId, PageRequest.of(0, ReplayLimits.MAX_INPUT_RECORDS))

    companion object {
        private const val RECORDS = "wallet events"
    }
}
