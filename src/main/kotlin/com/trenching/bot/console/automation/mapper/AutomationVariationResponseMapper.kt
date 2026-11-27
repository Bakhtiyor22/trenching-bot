package com.trenching.bot.console.automation.mapper

import com.trenching.bot.console.automation.api.dto.AutomationVariationListResponse
import com.trenching.bot.console.automation.api.dto.AutomationVariationResponse
import com.trenching.bot.console.automation.domain.VariationList
import com.trenching.bot.console.automation.domain.VariationView
import com.trenching.bot.console.mapper.ConfigResponseMapper
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConfigResponseMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface AutomationVariationResponseMapper {
    fun toList(list: VariationList): AutomationVariationListResponse

    @Mapping(target = ".", source = "variation")
    @Mapping(target = "name", source = "name.text")
    fun toResponse(view: VariationView): AutomationVariationResponse
}
