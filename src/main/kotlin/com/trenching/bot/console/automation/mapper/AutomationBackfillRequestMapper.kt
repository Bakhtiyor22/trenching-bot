package com.trenching.bot.console.automation.mapper

import com.trenching.bot.console.automation.api.dto.AutomationBackfillCreateRequest
import com.trenching.bot.console.automation.domain.BackfillRequest
import org.mapstruct.Mapper
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

/** A create request as the backfill rules take it; they, not the mapper, decide whether it is valid. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface AutomationBackfillRequestMapper {
    fun toRequest(request: AutomationBackfillCreateRequest): BackfillRequest
}
