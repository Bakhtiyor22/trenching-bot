package com.trenching.bot.marketdata.mapper

import com.trenching.bot.common.*
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.infrastructure.persistence.MarketObservationEntity
import org.mapstruct.*

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ChainValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class MarketObservationMapper {
    @Mappings(value = [
        Mapping(target = "id", expression = "java(storageId(runId, observation.getId()))"),
        Mapping(target = "runId", source = "runId"),
        Mapping(target = "observationId", source = "observation.id"),
        Mapping(target = "baseMint", source = "observation.pair.base.value"),
        Mapping(target = "quoteMint", source = "observation.pair.quote.value"),
        Mapping(target = "slot", source = "observation.slot.value"),
        Mapping(target = "priceSlot", source = "observation.priceSlot.value"),
        Mapping(target = "observedTime", source = "observation.observedTime"),
        Mapping(target = "price", source = "observation.price"),
        Mapping(target = "priceScale", expression = "java(observation.getPrice().scale())"),
        Mapping(target = "liquidityQuote", source = "observation.liquidityQuote"),
        Mapping(target = "liquidityScale", expression = "java(observation.getLiquidityQuote() == null ? null : observation.getLiquidityQuote().scale())"),
        Mapping(target = "source", source = "observation.source"),
        Mapping(target = "sourceEventId", source = "observation.sourceEventId"),
        Mapping(target = "commitment", source = "observation.commitment"),
        Mapping(target = "quality", source = "observation.quality"),
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toEntity(runId: String, observation: MarketObservation): MarketObservationEntity

    @Mappings(value = [
        Mapping(target = "id", source = "observationId"),
        Mapping(target = "pair", expression = "java(pair(entity))"),
        Mapping(target = "price", expression = "java(originalPrice(entity))"),
        Mapping(target = "liquidityQuote", expression = "java(originalLiquidity(entity))"),
    ])
    abstract fun toObservation(entity: MarketObservationEntity): MarketObservation

    fun storageId(runId: String, observationId: String): String = RecordIdentity.of(runId, observationId).value
    fun pair(entity: MarketObservationEntity): MarketPair = ChainValueMapper.pair(entity.baseMint, entity.quoteMint)
    fun originalPrice(entity: MarketObservationEntity): java.math.BigDecimal =
        ChainValueMapper.originalDecimal(entity.price, entity.priceScale)
    fun originalLiquidity(entity: MarketObservationEntity): java.math.BigDecimal? =
        entity.liquidityQuote?.let { ChainValueMapper.originalDecimal(it, requireNotNull(entity.liquidityScale)) }
}
