package com.trenching.bot.papertrading.mapper

import com.trenching.bot.common.ChainValueMapper
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.papertrading.infrastructure.persistence.*
import com.trenching.bot.strategy.domain.TradeIntent
import org.mapstruct.*

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ChainValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class PaperOrderMapper {
    @Mappings(value = [
        Mapping(target = "id", source = "intent.id"),
        Mapping(target = "runId", source = "runId"),
        Mapping(target = "targetSlot", source = "intent.targetSlot.value"),
        Mapping(target = "status", expression = "java(status(outcome))"),
        Mapping(target = "rejectionReason", expression = "java(reason(outcome))"),
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toOrder(runId: String, intent: TradeIntent, outcome: FillOutcome): PaperOrderEntity

    @Mappings(value = [
        Mapping(target = "runId", source = "runId"),
        Mapping(target = "orderStatus", constant = "FILLED"),
        Mapping(target = "targetSlot", source = "fill.targetSlot.value"),
        Mapping(target = "actualSlot", source = "fill.actualSlot.value"),
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toEntity(runId: String, fill: PaperFill): PaperFillEntity

    abstract fun toFill(entity: PaperFillEntity): PaperFill

    fun status(outcome: FillOutcome): OrderStatus = when (outcome) {
        is FillOutcome.Filled -> OrderStatus.FILLED
        is FillOutcome.Rejected -> OrderStatus.REJECTED
    }
    fun reason(outcome: FillOutcome): RejectionReason? = (outcome as? FillOutcome.Rejected)?.reason

    fun outcome(entity: PaperOrderEntity, fill: PaperFill?): FillOutcome = when (entity.status) {
        OrderStatus.FILLED -> FillOutcome.Filled(requireNotNull(fill) { "Missing fill for order=${entity.id}" })
        OrderStatus.REJECTED -> FillOutcome.Rejected(entity.id, requireNotNull(entity.rejectionReason))
    }
}
