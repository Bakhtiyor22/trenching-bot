package com.trenching.bot.support

import javax.sql.DataSource
import kotlin.test.assertTrue

private val RUN_TABLES = listOf("simulation_runs", "wallet_events", "market_observations", "trade_intents",
    "paper_orders", "paper_fills", "positions")

fun runRowCounts(dataSource: DataSource, runId: String): List<Int> = dataSource.connection.use { connection ->
    RUN_TABLES.map { table ->
        val key = if (table == "simulation_runs") "id" else "run_id"
        connection.prepareStatement("select count(*) from $table where $key = ?").use { statement ->
            statement.setString(1, runId)
            statement.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
    }
}
