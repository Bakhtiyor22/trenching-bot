package com.trenching.bot.replay.mapper

import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.domain.RunSummary
import com.trenching.bot.replay.domain.RunTotals
import com.trenching.bot.replay.domain.SimulationRunRecord
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunEntity
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunSummaryRow
import java.time.Instant
import org.mapstruct.*

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
abstract class SimulationRunMapper {
    @Mappings(value = [
        Mapping(target = "quoteMint", source = "totals.quoteMint"),
        Mapping(target = "scenarioCount", source = "totals.scenarioCount"),
        Mapping(target = "filledCount", source = "totals.filledCount"),
        Mapping(target = "rejectedCount", source = "totals.rejectedCount"),
        Mapping(target = "openCount", source = "totals.openCount"),
        Mapping(target = "closedCount", source = "totals.closedCount"),
        Mapping(target = "realizedPnl", source = "totals.realizedPnl"),
        Mapping(target = "version", ignore = true),
    ])
    abstract fun toEntity(record: SimulationRunRecord): SimulationRunEntity

    @Mapping(target = "totals", source = "entity")
    abstract fun toRecord(entity: SimulationRunEntity): SimulationRunRecord

    abstract fun toTotals(entity: SimulationRunEntity): RunTotals

    abstract fun record(id: String, engineVersion: String, datasetChecksum: String, configurationHash: String,
                        trackedWallet: String, configurationSnapshot: String, datasetSnapshot: String,
                        reportSnapshot: String, resultChecksum: String, totals: RunTotals, createdAt: Instant): SimulationRunRecord

    @Mappings(value = [
        Mapping(target = "datasetId", source = "datasetChecksum"),
        Mapping(target = "quoteMint", source = "totals.quoteMint"),
        Mapping(target = "scenarioCount", source = "totals.scenarioCount"),
        Mapping(target = "filledCount", source = "totals.filledCount"),
        Mapping(target = "rejectedCount", source = "totals.rejectedCount"),
        Mapping(target = "openCount", source = "totals.openCount"),
        Mapping(target = "closedCount", source = "totals.closedCount"),
        Mapping(target = "realizedPnl", source = "totals.realizedPnl"),
    ])
    abstract fun toSummary(record: SimulationRunRecord): RunSummary

    @Mapping(target = "datasetId", source = "datasetChecksum")
    abstract fun toSummary(row: SimulationRunSummaryRow): RunSummary

    fun mint(value: String): MintAddress = MintAddress.of(value)
    fun mint(value: MintAddress): String = value.value
}
