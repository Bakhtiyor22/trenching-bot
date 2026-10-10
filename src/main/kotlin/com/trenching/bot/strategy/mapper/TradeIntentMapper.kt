package com.trenching.bot.strategy.mapper

import com.trenching.bot.common.*
import com.trenching.bot.strategy.domain.DipTrigger
import com.trenching.bot.strategy.domain.TradeIntent
import com.trenching.bot.strategy.infrastructure.persistence.TradeIntentEntity
import org.mapstruct.*

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ChainValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class TradeIntentMapper {
    @Mappings(value = [
        Mapping(target = "baseMint", source = "pair.base.value"),
        Mapping(target = "quoteMint", source = "pair.quote.value"),
        Mapping(target = "creationSlot", source = "creationSlot.value"),
        Mapping(target = "targetSlot", source = "targetSlot.value"),
        Mapping(target = "horizonEndSlot", source = "horizonEndSlot.value"),
        Mapping(target = "triggerObservationId", source = "trigger.observationId"),
        Mapping(target = "triggerSlot", source = "trigger.slot.value"),
        Mapping(target = "triggerPrice", source = "trigger.price"),
        Mapping(target = "triggerMarketCapUsd", source = "trigger.marketCapUsd"),
        Mapping(target = "triggerRate", source = "trigger.rate"),
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toEntity(intent: TradeIntent): TradeIntentEntity

    @Mappings(value = [
        Mapping(target = "pair", expression = "java(pair(entity))"),
        Mapping(target = "referencePrice", expression = "java(required(entity.getReferencePrice(), entity))"),
        Mapping(target = "dipPrice", expression = "java(required(entity.getDipPrice(), entity))"),
        Mapping(target = "horizonEndSlot", expression = "java(Slot.Companion.of(required(entity.getHorizonEndSlot(), entity)))"),
        Mapping(target = "watch", expression = "java(required(entity.getWatch(), entity))"),
        Mapping(target = "trigger", expression = "java(trigger(entity))"),
    ])
    abstract fun toIntent(entity: TradeIntentEntity): TradeIntent

    fun pair(entity: TradeIntentEntity): MarketPair = ChainValueMapper.pair(entity.baseMint, entity.quoteMint)
    fun <T : Any> required(value: T?, entity: TradeIntentEntity): T = requireNotNull(value) { "Intent without dip fields id=${entity.id}" }
    fun trigger(entity: TradeIntentEntity): DipTrigger? = entity.triggerObservationId?.let { observationId ->
        DipTrigger(observationId, Slot.of(required(entity.triggerSlot, entity)), required(entity.triggerPrice, entity),
            required(entity.triggerMarketCapUsd, entity), required(entity.triggerRate, entity))
    }
}
