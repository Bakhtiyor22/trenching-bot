package com.trenching.bot.console.mapper

import com.trenching.bot.common.PageResult
import com.trenching.bot.console.api.dto.DatasetPageResponse
import com.trenching.bot.console.api.dto.DatasetRegistrationResponse
import com.trenching.bot.console.api.dto.DatasetResponse
import com.trenching.bot.console.domain.DatasetDetail
import com.trenching.bot.replay.domain.DatasetRegistration
import com.trenching.bot.replay.domain.DatasetSummary
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    uses = [ConsoleValueMapper::class, PairResponseMapper::class, DatasetSourceResponseMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface DatasetResponseMapper {
    @Mapping(target = "source", ignore = true)
    fun toResponse(dataset: DatasetSummary): DatasetResponse

    @Mapping(target = ".", source = "dataset")
    fun toDetail(detail: DatasetDetail): DatasetResponse

    fun toResponse(registration: DatasetRegistration): DatasetRegistrationResponse

    fun toPage(page: PageResult<DatasetSummary>): DatasetPageResponse
}
