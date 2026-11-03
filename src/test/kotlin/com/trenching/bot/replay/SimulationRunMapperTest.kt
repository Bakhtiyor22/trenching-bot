package com.trenching.bot.replay

import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.RunTotals
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunEntity
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunSummaryRow
import com.trenching.bot.replay.mapper.SimulationRunMapperImpl
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class SimulationRunMapperTest {
    private val mapper = SimulationRunMapperImpl()

    @Test
    fun `a run stored before run storage existed reads as a copy`() {
        assertEquals(RunStorage.COPY, mapper.toRecord(entity(storage = null)).storage)
        assertEquals(RunStorage.COPY, mapper.toSummary(row(storage = null)).storage)
    }

    @Test
    fun `a stored storage is read as stored`() {
        assertEquals(RunStorage.DATASET_REFERENCE, mapper.toRecord(entity(storage = RunStorage.DATASET_REFERENCE, snapshot = null)).storage)
        assertEquals(RunStorage.DATASET_REFERENCE, mapper.toSummary(row(storage = RunStorage.DATASET_REFERENCE)).storage)
        assertEquals(RunStorage.COPY, mapper.toSummary(row(storage = RunStorage.COPY)).storage)
    }

    @Test
    fun `a dataset-referenced record is stored with its storage and without a dataset snapshot`() {
        val record = mapper.record("run-1", "engine", "dataset-1", "config", WALLET, "{}", null, "{}", "result", TOTALS, CREATED_AT,
            RunStorage.DATASET_REFERENCE)

        val entity = mapper.toEntity(record)

        assertEquals(RunStorage.DATASET_REFERENCE, entity.storage)
        assertNull(entity.datasetSnapshot)
        assertEquals(RunStorage.DATASET_REFERENCE, mapper.toSummary(record).storage)
    }

    @Test
    fun `a copy record is stored as a copy with its dataset snapshot`() {
        val record = mapper.record("run-1", "engine", "dataset-1", "config", WALLET, "{}", "{\"dataset\":1}", "{}", "result", TOTALS,
            CREATED_AT, RunStorage.COPY)

        val entity = mapper.toEntity(record)

        assertEquals(RunStorage.COPY, entity.storage)
        assertEquals("{\"dataset\":1}", entity.datasetSnapshot)
    }

    private fun entity(storage: RunStorage?, snapshot: String? = "{}") = SimulationRunEntity(
        id = "run-1", engineVersion = "engine", datasetChecksum = "dataset-1", configurationHash = "config", trackedWallet = WALLET,
        configurationSnapshot = "{}", datasetSnapshot = snapshot, storage = storage, reportSnapshot = "{}", resultChecksum = "result",
        quoteMint = MintAddress.WRAPPED_SOL.value, scenarioCount = 0, filledCount = 0, rejectedCount = 0, openCount = 0, closedCount = 0,
        realizedPnl = BigDecimal.ZERO, createdAt = CREATED_AT,
    )

    private fun row(storage: RunStorage?) = object : SimulationRunSummaryRow {
        override val id = "run-1"
        override val engineVersion = "engine"
        override val datasetChecksum = "dataset-1"
        override val configurationHash = "config"
        override val resultChecksum = "result"
        override val quoteMint = MintAddress.WRAPPED_SOL.value
        override val scenarioCount = 0
        override val filledCount = 0
        override val rejectedCount = 0
        override val openCount = 0
        override val closedCount = 0
        override val realizedPnl: BigDecimal = BigDecimal.ZERO
        override val createdAt = CREATED_AT
        override val storage = storage
    }

    private companion object {
        const val WALLET = "DecuWa11et1111111111111111111111111111111111"
        val CREATED_AT: Instant = Instant.parse("2026-11-02T00:00:00Z")
        val TOTALS = RunTotals(MintAddress.WRAPPED_SOL, 0, 0, 0, 0, 0, BigDecimal.ZERO)
    }
}
