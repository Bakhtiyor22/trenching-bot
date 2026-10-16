package com.trenching.bot.console.mapper

import com.trenching.bot.common.PageResult
import com.trenching.bot.console.api.dto.CloseResponse
import com.trenching.bot.console.api.dto.FillResponse
import com.trenching.bot.console.api.dto.OrderResponse
import com.trenching.bot.console.api.dto.PositionResponse
import com.trenching.bot.console.api.dto.RunDetailResponse
import com.trenching.bot.console.api.dto.RunEventResponse
import com.trenching.bot.console.api.dto.RunPageResponse
import com.trenching.bot.console.api.dto.RunStartResponse
import com.trenching.bot.console.api.dto.RunSummaryResponse
import com.trenching.bot.console.api.dto.ScenarioPositionResponse
import com.trenching.bot.console.api.dto.ScenarioResponse
import com.trenching.bot.console.domain.IngestionStatus
import com.trenching.bot.console.domain.RunDetail
import com.trenching.bot.console.domain.RunEvent
import com.trenching.bot.console.domain.RunOrder
import com.trenching.bot.console.domain.RunPosition
import com.trenching.bot.console.domain.RunScenario
import com.trenching.bot.papertrading.domain.FillOutcome
import com.trenching.bot.papertrading.domain.OrderStatus
import com.trenching.bot.papertrading.domain.PaperFill
import com.trenching.bot.portfolio.domain.Position
import com.trenching.bot.portfolio.domain.PositionClose
import com.trenching.bot.replay.domain.RunStart
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.walletactivity.domain.NormalizationOutcome
import com.trenching.bot.walletactivity.domain.WalletTradeObserved
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.Mappings
import org.mapstruct.ReportingPolicy

/** Flattens the sealed outcomes: each variant is passed as its own nullable source so MapStruct null-guards it. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    uses = [ConsoleValueMapper::class, PairResponseMapper::class, ObservationResponseMapper::class, ConfigResponseMapper::class,
        PercentMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class RunResponseMapper {
    abstract fun toResponse(run: RunSummary): RunSummaryResponse

    abstract fun toResponse(start: RunStart): RunStartResponse

    abstract fun toPage(page: PageResult<RunSummary>): RunPageResponse

    abstract fun toResponse(detail: RunDetail): RunDetailResponse

    fun toScenario(scenario: RunScenario): ScenarioResponse =
        scenario(scenario, (scenario.outcome as? FillOutcome.Filled)?.fill, scenario.outcome as? FillOutcome.Rejected)

    @Mappings(value = [
        Mapping(target = "intentId", source = "scenario.intent.id"),
        Mapping(target = "sourceEventId", source = "scenario.intent.sourceEventId"),
        Mapping(target = "sourceSlot", source = "scenario.intent.creationSlot"),
        Mapping(target = "entryOffset", source = "scenario.intent.entryOffset"),
        Mapping(target = "targetSlot", source = "scenario.intent.targetSlot"),
        Mapping(target = "pair", source = "scenario.intent.pair"),
        Mapping(target = "notional", source = "scenario.intent.notional"),
        Mapping(target = "referencePrice", source = "scenario.intent.referencePrice"),
        Mapping(target = "dipPrice", source = "scenario.intent.dipPrice"),
        Mapping(target = "horizonEndSlot", source = "scenario.intent.horizonEndSlot"),
        Mapping(target = "watch", source = "scenario.intent.watch"),
        Mapping(target = "trigger", source = "scenario.intent.trigger"),
        Mapping(target = "status", expression = "java(status(scenario.getOutcome()))"),
        Mapping(target = "rejectionReason", source = "rejected.reason"),
        Mapping(target = "fill", source = "fill"),
        Mapping(target = "selectedObservation", source = "scenario.selectedObservation"),
        Mapping(target = "position", source = "scenario.position"),
        Mapping(target = "exitUnavailableReason", source = "scenario.exitUnavailable"),
    ])
    abstract fun scenario(scenario: RunScenario, fill: PaperFill?, rejected: FillOutcome.Rejected?): ScenarioResponse

    abstract fun toScenarioPosition(position: Position): ScenarioPositionResponse

    @Mappings(value = [
        Mapping(target = "feePercent", source = "feeBps", qualifiedByName = [PercentMapper.PERCENT]),
        Mapping(target = "priceImpactPercent", source = "priceImpactBps", qualifiedByName = [PercentMapper.PERCENT]),
    ])
    abstract fun toFill(fill: PaperFill): FillResponse

    @Mappings(value = [
        Mapping(target = "feePercent", source = "feeBps", qualifiedByName = [PercentMapper.PERCENT]),
        Mapping(target = "priceImpactPercent", source = "priceImpactBps", qualifiedByName = [PercentMapper.PERCENT]),
    ])
    abstract fun toClose(close: PositionClose): CloseResponse

    fun toEvent(event: RunEvent): RunEventResponse = event(event, (event.outcome as? NormalizationOutcome.Accepted)?.trade,
        event.outcome as? NormalizationOutcome.Ignored)

    @Mappings(value = [
        Mapping(target = ".", source = "event.transaction"),
        Mapping(target = "eventId", source = "event.eventId"),
        Mapping(target = "outcome", expression = "java(ingestionStatus(event.getOutcome()))"),
        Mapping(target = "side", source = "accepted.side"),
        Mapping(target = "ignoredReason", source = "ignored.reason"),
    ])
    abstract fun event(event: RunEvent, accepted: WalletTradeObserved?, ignored: NormalizationOutcome.Ignored?): RunEventResponse

    abstract fun toEvents(events: List<RunEvent>): List<RunEventResponse>

    fun toOrder(order: RunOrder): OrderResponse =
        order(order, (order.outcome as? FillOutcome.Filled)?.fill, order.outcome as? FillOutcome.Rejected)

    @Mappings(value = [
        Mapping(target = "orderId", source = "order.intent.id"),
        Mapping(target = "intentId", source = "order.intent.id"),
        Mapping(target = "entryOffset", source = "order.intent.entryOffset"),
        Mapping(target = "targetSlot", source = "order.intent.targetSlot"),
        Mapping(target = "status", expression = "java(status(order.getOutcome()))"),
        Mapping(target = "rejectionReason", source = "rejected.reason"),
        Mapping(target = "fill", source = "fill"),
    ])
    abstract fun order(order: RunOrder, fill: PaperFill?, rejected: FillOutcome.Rejected?): OrderResponse

    abstract fun toOrders(orders: List<RunOrder>): List<OrderResponse>

    @Mappings(value = [
        Mapping(target = ".", source = "position.position"),
        Mapping(target = "exitUnavailableReason", source = "position.exitUnavailable"),
    ])
    abstract fun toPosition(position: RunPosition): PositionResponse

    abstract fun toPositions(positions: List<RunPosition>): List<PositionResponse>

    fun status(outcome: FillOutcome): String = when (outcome) {
        is FillOutcome.Filled -> OrderStatus.FILLED.name
        is FillOutcome.Rejected -> OrderStatus.REJECTED.name
    }

    fun ingestionStatus(outcome: NormalizationOutcome): String = when (outcome) {
        is NormalizationOutcome.Accepted -> IngestionStatus.ACCEPTED.name
        is NormalizationOutcome.Ignored -> IngestionStatus.IGNORED.name
    }
}
