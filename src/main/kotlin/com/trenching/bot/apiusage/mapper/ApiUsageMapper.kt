package com.trenching.bot.apiusage.mapper

import com.trenching.bot.apiusage.ApiUsageTotals
import com.trenching.bot.apiusage.domain.ApiCallAggregate
import com.trenching.bot.apiusage.domain.ApiSeriesAggregate
import com.trenching.bot.apiusage.domain.ApiTaskAggregate
import com.trenching.bot.apiusage.infrastructure.persistence.ApiCallUsageRow
import com.trenching.bot.apiusage.infrastructure.persistence.ApiSeriesUsageRow
import com.trenching.bot.apiusage.infrastructure.persistence.ApiTaskUsageRow
import com.trenching.bot.apiusage.infrastructure.persistence.ApiUsageTotalsRow
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ApiUsageMapper {
    @Mapping(target = "totals", source = "row")
    fun toCall(row: ApiCallUsageRow): ApiCallAggregate

    @Mapping(target = "totals", source = "row")
    fun toTask(row: ApiTaskUsageRow): ApiTaskAggregate

    fun toSeries(row: ApiSeriesUsageRow): ApiSeriesAggregate

    fun toTotals(row: ApiUsageTotalsRow): ApiUsageTotals
}
