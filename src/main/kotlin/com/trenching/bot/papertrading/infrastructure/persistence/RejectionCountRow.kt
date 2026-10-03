package com.trenching.bot.papertrading.infrastructure.persistence

import com.trenching.bot.papertrading.domain.RejectionReason

interface RejectionCountRow {
    val reason: RejectionReason
    val count: Long
}
