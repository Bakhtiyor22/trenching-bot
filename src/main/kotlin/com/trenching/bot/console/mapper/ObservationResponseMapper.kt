package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.ObservationResponse
import com.trenching.bot.marketdata.domain.MarketObservation
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class, PairResponseMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ObservationResponseMapper {
    fun toResponse(observation: MarketObservation): ObservationResponse

    fun toResponses(observations: List<MarketObservation>): List<ObservationResponse>
}
