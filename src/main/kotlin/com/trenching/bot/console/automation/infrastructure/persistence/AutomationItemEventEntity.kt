package com.trenching.bot.console.automation.infrastructure.persistence

import com.trenching.bot.console.automation.domain.AutomationItemEventKind
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.AutomationStep
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import org.hibernate.annotations.ColumnTransformer
import org.springframework.data.domain.Persistable

/**
 * One append-only event of an item's history (plan Domain Rules H1–H5), written in the transaction of the change it records and
 * never updated or deleted. [seq] orders an item's events, from 1 and without gaps. [itemId] is not a foreign key: the events of
 * an item a backfill replaced stay, and the item that takes its place carries on their sequence.
 *
 * The id is assigned, so the row is [Persistable]: a new row is always inserted, and a second row for the same item and [seq]
 * fails on the unique key instead of replacing the first. [detail] is the JSON text of a `jsonb` column.
 */
@Entity
@Table(
    name = "automation_item_events",
    uniqueConstraints = [UniqueConstraint(name = "uq_automation_item_events_seq", columnNames = ["item_id", "seq"])],
    indexes = [Index(name = "idx_automation_item_events_at", columnList = "at")],
)
class AutomationItemEventEntity(
    @Id @Column(name = "id", nullable = false, length = 36) private var id: String,
    @Column(name = "item_id", nullable = false, length = 64) var itemId: String,
    @Column(name = "seq", nullable = false) var seq: Int,
    @Column(name = "at", nullable = false) var at: Instant,
    @Enumerated(EnumType.STRING) @Column(name = "kind", nullable = false, length = 32) var kind: AutomationItemEventKind,
    @Enumerated(EnumType.STRING) @Column(name = "from_state", length = 24) var fromState: AutomationItemState?,
    @Enumerated(EnumType.STRING) @Column(name = "to_state", length = 24) var toState: AutomationItemState?,
    @Enumerated(EnumType.STRING) @Column(name = "step", length = 16) var step: AutomationStep?,
    @Column(name = "attempt") var attempt: Int?,
    @Column(name = "capture_session_id", length = 80) var captureSessionId: String?,
    @Column(name = "operation_id", length = 80) var operationId: String?,
    @Column(name = "requests_used") var requestsUsed: Int?,
    @Column(name = "failure_code", length = 64) var failureCode: String?,
    @Column(name = "failure_message", length = 512) var failureMessage: String?,
    @Column(name = "actor", length = 80) var actor: String?,
    @Column(name = "request_id", length = 80) var requestId: String?,
    @Column(name = "note", length = 200) var note: String?,
    @ColumnTransformer(write = "?::jsonb") @Column(name = "detail", columnDefinition = "jsonb") var detail: String?,
) : Persistable<String> {
    @Transient
    private var stored: Boolean = false

    override fun getId(): String = id

    override fun isNew(): Boolean = !stored

    @PostPersist
    @PostLoad
    fun markStored() {
        stored = true
    }
}
