package com.trenching.bot.console.automation.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/** A discovery cursor kept beside a watermark: the newest action of a catch-up in progress. All four columns are null otherwise. */
@Embeddable
class DiscoveryCursorColumns(
    @Column(name = "pending_slot", nullable = true) var slot: Long,
    @Column(name = "pending_signature", nullable = true, length = 88) var signature: String,
    @Column(name = "pending_instruction_index", nullable = true) var instructionIndex: Int,
    @Column(name = "pending_inner_index", nullable = true) var innerIndex: Int,
)
