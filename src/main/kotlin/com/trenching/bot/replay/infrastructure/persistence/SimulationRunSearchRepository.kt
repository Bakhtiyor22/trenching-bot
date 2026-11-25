package com.trenching.bot.replay.infrastructure.persistence

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.replay.domain.RunSearchQuery
import com.trenching.bot.replay.domain.RunStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import java.math.BigDecimal
import java.math.RoundingMode
import org.springframework.stereotype.Repository

/** Query-only: finds the run ids of a search and its status aggregates; the rows themselves are read by id. */
@Repository
class SimulationRunSearchRepository(private val entityManager: EntityManager) {
    fun pageIds(query: RunSearchQuery): List<String> =
        run(RunSearchSql(query).pageIds()).resultList.map { it as String }

    fun statusAggregates(query: RunSearchQuery): List<RunStatusAggregate> =
        run(RunSearchSql(query).statusAggregates()).resultList.map { row ->
            (row as Array<*>).let {
                RunStatusAggregate(
                    RunStatus.valueOf(it[0] as String), it[1] as String, (it[2] as Number).toLong(),
                    (it[3] as BigDecimal).setScale(DecimalPolicy.SCALE, RoundingMode.UNNECESSARY),
                )
            }
        }

    private fun run(statement: SqlStatement): Query {
        val query = entityManager.createNativeQuery(statement.sql)
        statement.parameters.forEach { (name, value) -> query.setParameter(name, value) }
        return query
    }
}
