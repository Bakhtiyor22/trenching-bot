package com.trenching.bot.replay.infrastructure.persistence

import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SimulationRunRepository : JpaRepository<SimulationRunEntity, String> {
    fun findOneById(id: String): SimulationRunEntity?

    fun findSummaryById(id: String): SimulationRunSummaryRow?

    fun findAllProjectedBy(pageable: Pageable): Page<SimulationRunSummaryRow>

    fun findAllByDatasetChecksum(datasetChecksum: String, pageable: Pageable): Page<SimulationRunSummaryRow>

    @Query("select r.reportSnapshot from SimulationRunEntity r where r.id = :id")
    fun findReportSnapshotById(@Param("id") id: String): String?

    @Query("""
        select r.quoteMint as quoteMint, count(r) as runCount, sum(r.filledCount) as filledCount,
            sum(r.rejectedCount) as rejectedCount, sum(r.openCount) as openCount, sum(r.closedCount) as closedCount,
            sum(r.realizedPnl) as realizedPnl
        from SimulationRunEntity r group by r.quoteMint order by r.quoteMint
    """)
    fun sumByQuoteMint(): List<SimulationRunQuoteMintTotalsRow>

    fun countByCreatedAtGreaterThanEqual(createdAt: Instant): Long
}
