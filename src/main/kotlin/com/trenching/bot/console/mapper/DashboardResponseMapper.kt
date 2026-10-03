package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.DashboardResponse
import com.trenching.bot.console.api.dto.DashboardRunsResponse
import com.trenching.bot.console.domain.DashboardSnapshot
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.Mappings
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface DashboardResponseMapper {
    @Mappings(value = [
        Mapping(target = "runs", source = "snapshot"),
        Mapping(target = "rejectionsByReason", source = "rejections"),
    ])
    fun toResponse(snapshot: DashboardSnapshot): DashboardResponse

    @Mappings(value = [
        Mapping(target = "total", source = "totalRuns"),
        Mapping(target = "last24h", source = "runsLast24h"),
        Mapping(target = "recent", source = "recentRuns"),
    ])
    fun toRuns(snapshot: DashboardSnapshot): DashboardRunsResponse
}
