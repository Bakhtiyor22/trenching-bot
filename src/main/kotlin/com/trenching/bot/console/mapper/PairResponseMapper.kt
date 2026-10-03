package com.trenching.bot.console.mapper

import com.trenching.bot.common.MarketPair
import com.trenching.bot.console.api.dto.PairResponse
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface PairResponseMapper {
    fun toResponse(pair: MarketPair): PairResponse
}
