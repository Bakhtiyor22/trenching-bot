package com.trenching.bot.notification.infrastructure.persistence

import com.trenching.bot.notification.domain.NotificationState
import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface NotificationOutboxRepository : JpaRepository<NotificationOutboxEntity, String> {
    /**
     * Stores the row unless its key is taken. There is no conflict target on purpose: the id is the key's hash, so two transactions
     * publishing one key at once can meet on the primary key as well as on the unique key, and both must be skipped, never raised, or the
     * caller's transaction would be marked rollback-only.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
        insert into notification_outbox (id, notification_key, kind, payload, state, attempts, next_attempt_at, created_at)
        values (:id, :notificationKey, :kind, :payload, :state, 0, :createdAt, :createdAt)
        on conflict do nothing
    """)
    fun insertIfAbsent(
        @Param("id") id: String,
        @Param("notificationKey") notificationKey: String,
        @Param("kind") kind: String,
        @Param("payload") payload: String,
        @Param("state") state: String,
        @Param("createdAt") createdAt: Instant,
    ): Int

    fun findByStateAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(state: NotificationState, now: Instant, page: Pageable): List<NotificationOutboxEntity>

    @Modifying
    @Query(nativeQuery = true, value = """
        delete from notification_outbox where id in (
            select id from notification_outbox where state in ('SENT', 'FAILED') and created_at < :cutoff order by created_at limit :limit)
    """)
    fun deleteCreatedBefore(@Param("cutoff") cutoff: Instant, @Param("limit") limit: Int): Int
}
