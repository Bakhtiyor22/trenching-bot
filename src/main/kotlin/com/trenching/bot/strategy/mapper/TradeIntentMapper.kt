package com.trenching.bot.strategy.mapper

import com.trenching.bot.common.*
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
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toEntity(intent: TradeIntent): TradeIntentEntity

    @Mapping(target = "pair", expression = "java(pair(entity))")
    abstract fun toIntent(entity: TradeIntentEntity): TradeIntent

    fun pair(entity: TradeIntentEntity): MarketPair = ChainValueMapper.pair(entity.baseMint, entity.quoteMint)
}
