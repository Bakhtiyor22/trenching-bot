package com.trenching.bot.replay.infrastructure.persistence

import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.domain.RunStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.springframework.stereotype.Repository

/** Query-only: finds the run ids of a search and its status counts; the rows themselves are read by id. */
@Repository
class SimulationRunSearchRepository(private val entityManager: EntityManager) {
    fun pageIds(query: RunSearchQuery): List<String> =
        run(RunSearchSql(query).pageIds()).resultList.map { it as String }

    fun statusCounts(query: RunSearchQuery): Map<RunStatus, Long> {
        val counted = run(RunSearchSql(query).statusCounts()).resultList
            .associate { row -> (row as Array<*>).let { RunStatus.valueOf(it[0] as String) to (it[1] as Number).toLong() } }
        return RunStatus.entries.associateWith { counted[it] ?: 0L }
    }

    private fun run(statement: SqlStatement): Query {
        val query = entityManager.createNativeQuery(statement.sql)
        statement.parameters.forEach { (name, value) -> query.setParameter(name, value) }
        return query
    }
}
