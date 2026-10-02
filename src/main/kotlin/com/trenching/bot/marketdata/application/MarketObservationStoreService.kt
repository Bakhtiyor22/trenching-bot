package com.trenching.bot.marketdata.application

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.RecordBatch
import com.trenching.bot.common.ReplayLimits
import com.trenching.bot.common.TimePrecision
import com.trenching.bot.marketdata.MarketObservationStore
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.infrastructure.persistence.MarketObservationRepository
import com.trenching.bot.marketdata.mapper.MarketObservationMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MarketObservationStoreService(private val repository: MarketObservationRepository, private val mapper: MarketObservationMapper) : MarketObservationStore {
    @Transactional
    override fun record(runId: String, observations: List<MarketObservation>) {
        RecordBatch.requireWithinLimit("market observations", runId, observations.size, ReplayLimits.MAX_INPUT_RECORDS)
        observations.forEach {
            TimePrecision.requireMicroseconds(it.observedTime)
            DecimalPolicy.requireExactPrice(it.price)
            it.liquidityQuote?.let(DecimalPolicy::requireExactPrice)
        }
        repository.saveAllAndFlush(observations.map { mapper.toEntity(runId, it) })
    }

    @Transactional(readOnly = true)
    override fun observations(runId: String): List<MarketObservation> =
        repository.findByRunIdOrderBySlotAscObservationIdAsc(runId, PageRequest.of(0, ReplayLimits.MAX_INPUT_RECORDS)).map(mapper::toObservation)
}
