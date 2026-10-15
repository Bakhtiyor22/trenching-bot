package com.trenching.bot.replay.application

import com.trenching.bot.replay.domain.*
import com.trenching.bot.replay.mapper.SimulationRunMapper
import com.trenching.bot.config.SimulationClock
import org.springframework.beans.factory.annotation.Qualifier
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
    private val datasetEntries: DatasetEntryFactory,
    private val json: ObjectMapper,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SimulationRunLedger::class.java)

    fun persist(dataset: ReplayDataset, report: SimulationReport): SimulationRunRecord = persistWithOutcome(dataset, report).record

    /** Refuses, before anything is stored, a report with a scenario decided beyond its dataset's coverage. */
    fun persistWithOutcome(dataset: ReplayDataset, report: SimulationReport): RunPersistence {
        require(DatasetFingerprint.checksum(dataset) == report.datasetChecksum) { "Report does not belong to dataset" }
        require(StableHash.of(listOf(report.engineVersion, report.datasetChecksum, report.configurationHash)) == report.runId) {
            "Invalid simulation run identity"
        }
        DatasetCoverage.undecided(report, dataset).firstOrNull()?.let { shortfall ->
            log.warn("simulation run refused reason={} runId={} mint={} decidedAt={} throughSlot={}", VerdictReasons.MARKET_COVERAGE_ENDED,
                report.runId, shortfall.pair.base.value, shortfall.decidedAt.value, shortfall.throughSlot?.value)
            throw MarketCoverageEndedException(shortfall)
        }
        val createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val record = records.record(report.runId, report.engineVersion, report.datasetChecksum, report.configurationHash,
            dataset.trackedWallet.value, json.writeValueAsString(report.configuration), json.writeValueAsString(dataset),
            json.writeValueAsString(report), report.resultChecksum, RunTotals.of(report), createdAt)
        val datasetEntry = datasetEntries.entry(null, dataset, createdAt)
        val stored = try {
            transactions.insertWithOutcome(record, dataset, report, datasetEntry)
        } catch (exception: DataIntegrityViolationException) {
            val winner = transactions.find(record.id) ?: throw exception
            log.warn("duplicate simulation writer recovered runId={}", record.id)
            RunPersistence(winner, created = false)
        }
        if (stored.record.resultChecksum != record.resultChecksum || stored.record.datasetChecksum != record.datasetChecksum ||
            stored.record.configurationHash != record.configurationHash || stored.record.engineVersion != record.engineVersion) {
            throw SimulationRunConflictException(record.id)
        }
        return stored
    }

    fun find(id: String): SimulationRunRecord? = transactions.find(id)
}
