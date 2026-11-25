package com.trenching.bot.support

import com.trenching.bot.common.MintAddress
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetEntity
import com.trenching.bot.replay.infrastructure.persistence.ReplayDatasetRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunEntity
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunRepository
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenEntity
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenKey
import com.trenching.bot.replay.infrastructure.persistence.SimulationRunTokenRepository
import java.math.BigDecimal
import java.time.Instant
import javax.sql.DataSource

/** Writes run rows with chosen totals, times and tokens straight to the tables, so a search test controls every column. */
class RunSearchFixtures(
    private val runs: SimulationRunRepository,
    private val tokens: SimulationRunTokenRepository,
    private val datasets: ReplayDatasetRepository,
) {
    fun dataset(id: String, name: String = id, createdAt: Instant = Instant.parse("2026-11-01T00:00:00Z")) {
        datasets.saveAndFlush(ReplayDatasetEntity(id, name, WALLET, 6, 0, 0, null, null, "", "{}", createdAt))
    }

    @Suppress("LongParameterList")
    fun run(
        id: String,
        createdAt: Instant,
        datasetId: String = "dataset-a",
        filled: Int = 1,
        open: Int = 0,
        pnl: String = "0",
        anchor: Instant? = null,
        mints: List<MintAddress> = emptyList(),
        configurationHash: String = id,
        quote: String = MintAddress.WRAPPED_SOL.value,
    ) {
        val entity = SimulationRunEntity(id, "engine", datasetId, configurationHash, WALLET, "{}", null, RunStorage.DATASET_REFERENCE, "{}",
            "result-$id", quote, filled, filled, 0, open, filled - open, BigDecimal(pnl).setScale(12), createdAt)
        entity.anchorBlockTime = anchor
        entity.searchIndexedAt = createdAt
        runs.saveAndFlush(entity)
        tokens.saveAllAndFlush(mints.map { SimulationRunTokenEntity(SimulationRunTokenKey(id, it.value)) })
    }

    companion object {
        const val WALLET = "11111111111111111111111111111111"
        val MINT_A: MintAddress = MintAddress.of("11111111111111111111111111111112")
        val MINT_B: MintAddress = MintAddress.of("11111111111111111111111111111113")

        fun emptyTables(dataSource: DataSource) {
            dataSource.connection.use { connection ->
                val tables = connection.prepareStatement("select tablename from pg_tables where schemaname = current_schema()").use { statement ->
                    statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
                }
                connection.createStatement().use { it.execute("truncate table ${tables.joinToString()} cascade") }
            }
        }
    }
}
