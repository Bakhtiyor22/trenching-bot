package com.trenching.bot.replay.application

import com.trenching.bot.replay.domain.*
import com.trenching.bot.replay.mapper.SimulationRunMapper
import java.time.Clock
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class SimulationRunLedger(
    private val transactions: SimulationRunTransaction,
    private val records: SimulationRunMapper,
    private val json: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SimulationRunLedger::class.java)

    fun persist(dataset: ReplayDataset, report: SimulationReport): SimulationRunRecord {
        require(DatasetFingerprint.checksum(dataset) == report.datasetChecksum) { "Report does not belong to dataset" }
        require(StableHash.of(listOf(report.engineVersion, report.datasetChecksum, report.configurationHash)) == report.runId) {
            "Invalid simulation run identity"
        }
        val record = records.record(report.runId, report.engineVersion, report.datasetChecksum, report.configurationHash,
            dataset.trackedWallet.value, json.writeValueAsString(report.configuration), json.writeValueAsString(dataset),
            json.writeValueAsString(report), report.resultChecksum, clock.instant().truncatedTo(ChronoUnit.MICROS))
        val stored = try {
            transactions.insert(record, dataset, report)
        } catch (exception: DataIntegrityViolationException) {
            val winner = transactions.find(record.id) ?: throw exception
            log.warn("duplicate simulation writer recovered runId={}", record.id)
            winner
        }
        if (stored.resultChecksum != record.resultChecksum || stored.datasetChecksum != record.datasetChecksum ||
            stored.configurationHash != record.configurationHash || stored.engineVersion != record.engineVersion) {
            throw SimulationRunConflictException(record.id)
        }
        return stored
    }

    fun find(id: String): SimulationRunRecord? = transactions.find(id)
}
