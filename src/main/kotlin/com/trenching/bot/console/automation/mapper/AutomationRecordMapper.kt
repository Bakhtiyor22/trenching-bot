package com.trenching.bot.console.automation.mapper

import com.trenching.bot.console.automation.domain.AutomationDueItem
import com.trenching.bot.console.automation.domain.AutomationEvaluationView
import com.trenching.bot.console.automation.domain.AutomationItemProgress
import com.trenching.bot.console.automation.domain.AutomationItemView
import com.trenching.bot.console.automation.domain.AutomationSessionView
import com.trenching.bot.console.automation.domain.AutomationSweepRunView
import com.trenching.bot.console.automation.domain.AutomationWatermarkView
import com.trenching.bot.console.automation.domain.DiscoveryCursor
import com.trenching.bot.console.automation.domain.DiscoveryWatermark
import com.trenching.bot.console.automation.domain.EvaluationScore
import com.trenching.bot.console.automation.domain.SweepRunScore
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationEvaluationEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationEvaluationScoreRow
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkEntity
import com.trenching.bot.console.automation.infrastructure.persistence.DiscoveryCursorColumns
import org.mapstruct.BeanMapping
import org.mapstruct.Mapper
import org.mapstruct.Mapping
import org.mapstruct.MappingConstants
import org.mapstruct.MappingTarget
import org.mapstruct.ReportingPolicy

/** Automation rows to the views the services return; entities never leave the automation services. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, uses = [EvaluationWindowMapper::class], unmappedTargetPolicy = ReportingPolicy.ERROR)
interface AutomationRecordMapper {
    fun toSession(entity: AutomationSessionEntity): AutomationSessionView

    fun toItem(entity: AutomationItemEntity): AutomationItemView

    fun toProgress(entity: AutomationItemEntity): AutomationItemProgress

    @Mapping(target = "progress", source = ".")
    fun toDueItem(entity: AutomationItemEntity): AutomationDueItem

    /** Writes a pipeline step's outcome onto the item; its buy, ids and bookkeeping columns stay as they are. */
    @BeanMapping(unmappedTargetPolicy = ReportingPolicy.IGNORE)
    fun applyProgress(progress: AutomationItemProgress, @MappingTarget entity: AutomationItemEntity)

    fun toSweepRun(entity: AutomationSweepRunEntity): AutomationSweepRunView

    fun toSweepScore(entity: AutomationSweepRunEntity): SweepRunScore

    fun toEvaluation(entity: AutomationEvaluationEntity): AutomationEvaluationView

    fun toWatermark(entity: AutomationWatermarkEntity): AutomationWatermarkView

    fun toCursor(entity: AutomationWatermarkEntity): DiscoveryCursor

    fun toCursor(columns: DiscoveryCursorColumns): DiscoveryCursor

    /** The position of the item's buy, in discovery order. */
    fun toCursor(item: AutomationItemEntity): DiscoveryCursor

    fun toColumns(cursor: DiscoveryCursor): DiscoveryCursorColumns

    @Mapping(target = "cursor", source = ".")
    fun toDiscoveryWatermark(entity: AutomationWatermarkEntity): DiscoveryWatermark

    fun toScore(row: AutomationEvaluationScoreRow): EvaluationScore
}
