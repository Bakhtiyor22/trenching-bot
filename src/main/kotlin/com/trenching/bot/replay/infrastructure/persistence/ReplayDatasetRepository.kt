package com.trenching.bot.replay.infrastructure.persistence

import java.time.Instant
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReplayDatasetRepository : JpaRepository<ReplayDatasetEntity, String> {
    fun findSummaryById(id: String): ReplayDatasetSummaryRow?

    fun findAllProjectedBy(pageable: Pageable): Page<ReplayDatasetSummaryRow>

    /** Returns 1 when the dataset was removed, 0 when no dataset has the id. */
    @Modifying
    @Query("delete from ReplayDatasetEntity d where d.id = :id")
    fun deleteDataset(@Param("id") id: String): Int

    @Query("select d.snapshot from ReplayDatasetEntity d where d.id = :id")
    fun findSnapshotById(@Param("id") id: String): String?

    /** Returns 1 when inserted, 0 when the id exists; a conflict never aborts the surrounding transaction. */
    @Modifying
    @Query(nativeQuery = true, value = """
        insert into replay_datasets (id, name, tracked_wallet, schema_version, transaction_count, observation_count,
            first_slot, last_slot, pairs, snapshot, created_at, version)
        values (:id, :name, :trackedWallet, :schemaVersion, :transactionCount, :observationCount,
            :firstSlot, :lastSlot, :pairs, :snapshot, :createdAt, 0)
        on conflict (id) do nothing
    """)
    fun insertIfAbsent(
        @Param("id") id: String,
        @Param("name") name: String,
        @Param("trackedWallet") trackedWallet: String,
        @Param("schemaVersion") schemaVersion: Int,
        @Param("transactionCount") transactionCount: Int,
        @Param("observationCount") observationCount: Int,
        @Param("firstSlot") firstSlot: Long?,
        @Param("lastSlot") lastSlot: Long?,
        @Param("pairs") pairs: String,
        @Param("snapshot") snapshot: String,
        @Param("createdAt") createdAt: Instant,
    ): Int
}
