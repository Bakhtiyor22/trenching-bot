package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.ConfigDefaultsResponse
import com.trenching.bot.console.api.dto.ConfigLimitsResponse
import com.trenching.bot.console.api.dto.ConfigResponse
import com.trenching.bot.console.domain.ConfigDefaults
import com.trenching.bot.console.domain.SimulationConfigLimits
import com.trenching.bot.strategy.domain.SimulationConfig
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.Mappings
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class, PercentMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ConfigResponseMapper {
    @Mappings(value = [
        Mapping(target = "dipPercent", source = "dipBps", qualifiedByName = [PercentMapper.PERCENT]),
        Mapping(target = "takeProfitPercent", source = "takeProfitBps", qualifiedByName = [PercentMapper.PERCENT]),
        Mapping(target = "stopLossPercent", source = "stopLossBps", qualifiedByName = [PercentMapper.PERCENT]),
    ])
    fun toResponse(config: SimulationConfig): ConfigResponse

    @Mappings(value = [
        Mapping(target = "dipPercent", source = "dipBps", qualifiedByName = [PercentMapper.PERCENT_RANGE]),
        Mapping(target = "takeProfitPercent", source = "takeProfitBps", qualifiedByName = [PercentMapper.PERCENT_RANGE]),
        Mapping(target = "stopLossPercent", source = "stopLossBps", qualifiedByName = [PercentMapper.PERCENT_RANGE]),
    ])
    fun toResponse(limits: SimulationConfigLimits): ConfigLimitsResponse

    @Mapping(target = "defaults", source = "config")
    fun toResponse(defaults: ConfigDefaults): ConfigDefaultsResponse
}
