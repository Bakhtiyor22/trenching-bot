package com.trenching.bot.replay.infrastructure.persistence

import com.trenching.bot.replay.domain.RunStorage
import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SimulationRunRepository : JpaRepository<SimulationRunEntity, String> {
    fun findOneById(id: String): SimulationRunEntity?

    fun findSummaryById(id: String): SimulationRunSummaryRow?

    fun findAllProjectedBy(pageable: Pageable): Page<SimulationRunSummaryRow>

    fun findSummariesByIdIn(ids: Collection<String>): List<SimulationRunSummaryRow>

    /** One page of the runs after [afterId] that the backfill has neither indexed nor failed on: their small columns only, never a snapshot. */
    @Query("""
        select r.id as id, r.datasetChecksum as datasetChecksum, r.storage as storage from SimulationRunEntity r
        where r.id > :afterId and r.searchIndexedAt is null and r.searchIndexFailedAt is null order by r.id
    """)
    fun findIndexSourcesAfter(@Param("afterId") afterId: String, pageable: Pageable): List<SimulationRunIndexSource>

    /** One copy run's dataset snapshot, read on its own so a page never holds more than one. */
    @Query("select r.datasetSnapshot from SimulationRunEntity r where r.id = :id")
    fun findDatasetSnapshotById(@Param("id") id: String): String?

    /** Records that the backfill gave up on the run, so it is not read again. Returns 0 when the run was indexed meanwhile. */
    @Modifying
    @Query("update SimulationRunEntity r set r.searchIndexFailedAt = :at where r.id = :id and r.searchIndexedAt is null")
    fun markIndexFailed(@Param("id") id: String, @Param("at") at: Instant): Int

    /** Returns 1 when the run was not indexed yet and now is, 0 when it already was. */
    @Modifying
    @Query("update SimulationRunEntity r set r.anchorBlockTime = :anchor, r.searchIndexedAt = :at where r.id = :id and r.searchIndexedAt is null")
    fun markIndexed(@Param("id") id: String, @Param("anchor") anchor: Instant?, @Param("at") at: Instant): Int

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

    fun countByDatasetChecksumAndStorage(datasetChecksum: String, storage: RunStorage): Long
}
