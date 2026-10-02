package com.trenching.bot.replay.mapper

import com.trenching.bot.replay.domain.SimulationRunRecord
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunEntity
import java.time.Instant
import org.mapstruct.*

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface SimulationRunMapper {
    @Mapping(target = "version", ignore = true)
    fun toEntity(record: SimulationRunRecord): SimulationRunEntity

    fun toRecord(entity: SimulationRunEntity): SimulationRunRecord

    fun record(id: String, engineVersion: String, datasetChecksum: String, configurationHash: String,
               trackedWallet: String, configurationSnapshot: String, datasetSnapshot: String,
               reportSnapshot: String, resultChecksum: String, createdAt: Instant): SimulationRunRecord
}
