package com.trenching.bot.marketdata.mapper

import com.trenching.bot.common.*
import com.trenching.bot.marketdata.domain.CurveState
import com.trenching.bot.marketdata.domain.MarketObservation
import com.trenching.bot.marketdata.domain.ObservedTrade
import com.trenching.bot.marketdata.infrastructure.persistence.MarketObservationEntity
import com.trenching.bot.protocol.pumpfun.CurveReserves
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
        Mapping(target = "curveComplete", source = "observation.curveComplete"),
        Mapping(target = "virtualSolReserves", source = "observation.curve.reserves.virtualSol"),
        Mapping(target = "virtualTokenReserves", source = "observation.curve.reserves.virtualToken"),
        Mapping(target = "realSolReserves", source = "observation.curve.reserves.realSol"),
        Mapping(target = "realTokenReserves", source = "observation.curve.reserves.realToken"),
        Mapping(target = "feeBps", source = "observation.curve.feeBps"),
        Mapping(target = "creatorFeeBps", source = "observation.curve.creatorFeeBps"),
        Mapping(target = "tradeTrader", source = "observation.trade.trader"),
        Mapping(target = "tradeSide", source = "observation.trade.side"),
        Mapping(target = "tradeSolLamports", source = "observation.trade.solLamports"),
        Mapping(target = "tradeTokenRaw", source = "observation.trade.tokenRaw"),
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toEntity(runId: String, observation: MarketObservation): MarketObservationEntity

    @Mappings(value = [
        Mapping(target = "id", source = "observationId"),
        Mapping(target = "pair", expression = "java(pair(entity))"),
        Mapping(target = "price", expression = "java(originalPrice(entity))"),
        Mapping(target = "liquidityQuote", expression = "java(originalLiquidity(entity))"),
        Mapping(target = "curveComplete", expression = "java(curveComplete(entity))"),
        Mapping(target = "curve", expression = "java(curve(entity))"),
        Mapping(target = "trade", expression = "java(trade(entity))"),
    ])
    abstract fun toObservation(entity: MarketObservationEntity): MarketObservation

    fun storageId(runId: String, observationId: String): String = RecordIdentity.of(runId, observationId).value
    fun pair(entity: MarketObservationEntity): MarketPair = ChainValueMapper.pair(entity.baseMint, entity.quoteMint)
    fun originalPrice(entity: MarketObservationEntity): java.math.BigDecimal =
        ChainValueMapper.originalDecimal(entity.price, entity.priceScale)
    fun curveComplete(entity: MarketObservationEntity): Boolean = requireCurve(entity.curveComplete, entity)
    fun curve(entity: MarketObservationEntity): CurveState = CurveState(
        CurveReserves(requireCurve(entity.virtualSolReserves, entity), requireCurve(entity.virtualTokenReserves, entity),
            requireCurve(entity.realSolReserves, entity), requireCurve(entity.realTokenReserves, entity)),
        requireCurve(entity.feeBps, entity), requireCurve(entity.creatorFeeBps, entity))

    /** The stored trade, or null for a row stored before trades were kept; a row with only some trade columns is refused. */
    fun trade(entity: MarketObservationEntity): ObservedTrade? {
        val columns = listOf(entity.tradeTrader, entity.tradeSide, entity.tradeSolLamports, entity.tradeTokenRaw)
        if (columns.all { it == null }) return null
        require(columns.none { it == null }) { "Observation with a partial trade id=${entity.observationId}" }
        return ObservedTrade.of(requireNotNull(entity.tradeTrader), requireNotNull(entity.tradeSide),
            requireNotNull(entity.tradeSolLamports), requireNotNull(entity.tradeTokenRaw))
    }

    fun originalLiquidity(entity: MarketObservationEntity): java.math.BigDecimal? =
        entity.liquidityQuote?.let { ChainValueMapper.originalDecimal(it, requireNotNull(entity.liquidityScale)) }

    /** Only rows stored before curve state existed lack it; they cannot be replayed under the curve model. */
    private fun <T : Any> requireCurve(value: T?, entity: MarketObservationEntity): T =
        requireNotNull(value) { "Observation without curve state id=${entity.observationId}" }
}
