package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.ConfigDefaultsResponse
import com.trenching.bot.console.api.dto.ConfigResponse
import com.trenching.bot.console.domain.ConfigDefaults
import com.trenching.bot.strategy.domain.SimulationConfig
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ConfigResponseMapper {
    fun toResponse(config: SimulationConfig): ConfigResponse

    @Mapping(target = "defaults", source = "config")
    fun toResponse(defaults: ConfigDefaults): ConfigDefaultsResponse
}
