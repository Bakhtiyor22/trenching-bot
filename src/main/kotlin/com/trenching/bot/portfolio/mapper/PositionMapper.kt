package com.trenching.bot.portfolio.mapper

import com.trenching.bot.common.*
import com.trenching.bot.papertrading.domain.*
import com.trenching.bot.portfolio.domain.*
import com.trenching.bot.portfolio.infrastructure.persistence.PositionEntity
import org.mapstruct.*

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ChainValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class PositionMapper {
    @Mappings(value = [
        Mapping(target = "baseMint", source = "position.pair.base.value"),
        Mapping(target = "quoteMint", source = "position.pair.quote.value"),
        Mapping(target = "fillOrderId", source = "position.fill.orderId"),
        Mapping(target = "targetSlot", source = "position.fill.targetSlot.value"),
        Mapping(target = "closeSlot", source = "position.close.slot.value"),
        Mapping(target = "closeObservationId", source = "position.close.observationId"),
        Mapping(target = "exitPrice", source = "position.close.executionPrice"),
        Mapping(target = "grossProceeds", source = "position.close.grossProceeds"),
        Mapping(target = "exitFee", source = "position.close.fee"),
        Mapping(target = "closeFeeBps", source = "position.close.feeBps"),
        Mapping(target = "closeTransactionCost", source = "position.close.transactionCost"),
        Mapping(target = "closePriceImpactBps", source = "position.close.priceImpactBps"),
        Mapping(target = "netProceeds", source = "position.close.netProceeds"),
        Mapping(target = "realizedPnl", source = "position.close.realizedPnl"),
        Mapping(target = "closeReason", source = "position.close.reason"),
        Mapping(target = "closePriceAssumption", source = "position.close.priceAssumption"),
        Mapping(target = "exitUnavailable", source = "exitUnavailable"),
        Mapping(target = "exitTriggerObservationId", source = "position.exitTrigger.observationId"),
        Mapping(target = "exitTriggerSlot", source = "position.exitTrigger.slot.value"),
        Mapping(target = "exitTriggerTime", source = "position.exitTrigger.time"),
        Mapping(target = "exitTriggerReason", source = "position.exitTrigger.reason"),
        Mapping(target = "exitStart", source = "position.exitStart"),
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toEntity(position: Position, exitUnavailable: RejectionReason?): PositionEntity

    @Mappings(value = [
        Mapping(target = "id", source = "entity.id"),
        Mapping(target = "runId", source = "entity.runId"),
        Mapping(target = "intentId", source = "entity.intentId"),
        Mapping(target = "pair", expression = "java(pair(entity))"),
        Mapping(target = "entryOffset", source = "entity.entryOffset"),
        Mapping(target = "fill", source = "fill"),
        Mapping(target = "state", source = "entity.state"),
        Mapping(target = "close", expression = "java(close(entity))"),
        Mapping(target = "takeProfitPrice", expression = "java(required(entity.getTakeProfitPrice(), entity))"),
        Mapping(target = "stopLossPrice", expression = "java(required(entity.getStopLossPrice(), entity))"),
        Mapping(target = "exitTrigger", expression = "java(exitTrigger(entity))"),
        Mapping(target = "exitStart", source = "entity.exitStart"),
    ])
    abstract fun toPosition(entity: PositionEntity, fill: PaperFill): Position

    @Mappings(value = [
        Mapping(target = "slot", source = "closeSlot"),
        Mapping(target = "observationId", source = "closeObservationId"),
        Mapping(target = "executionPrice", source = "exitPrice"),
        Mapping(target = "fee", source = "exitFee"),
        Mapping(target = "feeBps", expression = "java(requiredClose(entity.getCloseFeeBps(), entity))"),
        Mapping(target = "transactionCost", expression = "java(requiredClose(entity.getCloseTransactionCost(), entity))"),
        Mapping(target = "priceImpactBps", expression = "java(requiredClose(entity.getClosePriceImpactBps(), entity))"),
        Mapping(target = "reason", source = "closeReason"),
        Mapping(target = "priceAssumption", expression = "java(priceAssumption(entity))"),
    ])
    abstract fun toClose(entity: PositionEntity): PositionClose

    fun pair(entity: PositionEntity): MarketPair = ChainValueMapper.pair(entity.baseMint, entity.quoteMint)
    fun close(entity: PositionEntity): PositionClose? = if (entity.state == PositionState.CLOSED) toClose(entity) else null
    fun required(value: java.math.BigDecimal?, entity: PositionEntity): java.math.BigDecimal =
        requireNotNull(value) { "Position without take-profit or stop-loss level id=${entity.id}" }
    fun <T : Any> requiredClose(value: T?, entity: PositionEntity): T =
        requireNotNull(value) { "Closed position without curve exit fields id=${entity.id}" }
    fun exitTrigger(entity: PositionEntity): ExitTrigger? = entity.exitTriggerObservationId?.let { observationId ->
        ExitTrigger(observationId, Slot.of(requireNotNull(entity.exitTriggerSlot)), entity.exitTriggerTime,
            requireNotNull(entity.exitTriggerReason))
    }
    fun priceAssumption(entity: PositionEntity): ExitPriceAssumption = entity.closePriceAssumption ?: ExitPriceAssumption.NEXT_TRADE
}
