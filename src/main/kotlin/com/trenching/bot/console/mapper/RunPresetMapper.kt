package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.RunPresetResponse
import com.trenching.bot.console.domain.RunPreset
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConfigResponseMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface RunPresetMapper {
    fun toResponse(preset: RunPreset): RunPresetResponse
}
