package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.DatasetSourceResponse
import com.trenching.bot.console.domain.DatasetSource
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.Mappings
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface DatasetSourceResponseMapper {
    @Mappings(value = [
        Mapping(target = "captureId", source = "capture.captureId"),
        Mapping(target = "captureDisplayName", source = "capture.displayName"),
        Mapping(target = "caseId", source = "capture.caseId"),
    ])
    fun toResponse(source: DatasetSource): DatasetSourceResponse
}
