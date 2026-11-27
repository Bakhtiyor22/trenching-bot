package com.trenching.bot.replay

import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.infrastructure.persistence.RunSearchSql
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RunSearchSqlTest {
    @Test
    fun `no hash filter adds no condition and no parameter`() {
        val statement = RunSearchSql(query(null)).pageIds()

        assertFalse(statement.sql.contains("configuration_hash"))
        assertFalse(statement.parameters.containsKey("configurationHashes"))
    }

    @Test
    fun `a hash set is an in condition bound as a list, in the page and the aggregates alike`() {
        val sql = RunSearchSql(query(setOf("h2", "h1")))

        listOf(sql.pageIds(), sql.statusAggregates()).forEach { statement ->
            assertTrue(statement.sql.contains("r.configuration_hash in (:configurationHashes)"), statement.sql)
            assertEquals(listOf("h1", "h2"), statement.parameters["configurationHashes"])
        }
    }

    @Test
    fun `an empty hash set is a false condition with no parameter, never an empty in list`() {
        val sql = RunSearchSql(query(emptySet()))

        listOf(sql.pageIds(), sql.statusAggregates()).forEach { statement ->
            assertTrue(statement.sql.contains("1 = 0"), statement.sql)
            assertFalse(statement.sql.contains("in ()"), statement.sql)
            assertFalse(statement.parameters.containsKey("configurationHashes"))
        }
    }

    @Test
    fun `no bound parameter is ever null`() {
        listOf(null, emptySet(), setOf("h1")).forEach { hashes ->
            val sql = RunSearchSql(query(hashes))

            assertTrue(sql.pageIds().parameters.values.none { it == null })
            assertTrue(sql.statusAggregates().parameters.values.none { it == null })
        }
    }

    private fun query(hashes: Set<String>?) = RunSearchQuery(
        emptySet(), null, null, null, null, null, null, null, Instant.parse("2026-11-26T00:00:00Z"), 0, 20, hashes,
    )
}
