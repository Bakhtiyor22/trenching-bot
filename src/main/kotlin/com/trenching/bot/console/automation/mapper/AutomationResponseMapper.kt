package com.trenching.bot.console.automation.mapper

import com.trenching.bot.common.PageResult
import com.trenching.bot.console.automation.api.dto.AutomationEvaluationPageResponse
import com.trenching.bot.console.automation.api.dto.AutomationItemDetailResponse
import com.trenching.bot.console.automation.api.dto.AutomationSessionDetailResponse
import com.trenching.bot.console.automation.api.dto.AutomationSessionPageResponse
import com.trenching.bot.console.automation.api.dto.AutomationSessionResponse
import com.trenching.bot.console.automation.api.dto.AutomationStatusResponse
import com.trenching.bot.console.automation.api.dto.AutomationSweepRunPageResponse
import com.trenching.bot.console.automation.api.dto.AutomationSweepRunRowResponse
import com.trenching.bot.console.automation.domain.AutomationEvaluationView
import com.trenching.bot.console.automation.domain.AutomationItemDetail
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationSessionDetail
import com.trenching.bot.console.automation.domain.AutomationSessionRow
import com.trenching.bot.console.automation.domain.AutomationStatus
import com.trenching.bot.console.automation.domain.AutomationSweepRunRow
import com.trenching.bot.console.mapper.ConsoleValueMapper
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.ReportingPolicy

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [ConsoleValueMapper::class, AutomationValueMapper::class],
    unmappedTargetPolicy = ReportingPolicy.ERROR)
interface AutomationResponseMapper {
    fun toStatus(status: AutomationStatus): AutomationStatusResponse

    fun toPage(page: PageResult<AutomationSessionRow>): AutomationSessionPageResponse

    fun toDetail(detail: AutomationSessionDetail): AutomationSessionDetailResponse

    fun toItemDetail(detail: AutomationItemDetail): AutomationItemDetailResponse

    @Mapping(target = ".", source = "session")
    fun toSession(row: AutomationSessionRow): AutomationSessionResponse

    fun toItemCounts(counts: Map<AutomationItemState, Long>): Map<String, Long>

    fun toEvaluationPage(page: PageResult<AutomationEvaluationView>): AutomationEvaluationPageResponse

    fun toSweepRunPage(page: PageResult<AutomationSweepRunRow>): AutomationSweepRunPageResponse

    @Mapping(target = ".", source = "run")
    fun toSweepRunRow(row: AutomationSweepRunRow): AutomationSweepRunRowResponse
}
