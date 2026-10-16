package com.trenching.bot.console.mapper

import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.capture.CaptureNames
import com.trenching.bot.console.capture.CaptureOperationRecord
import com.trenching.bot.console.capture.CaptureRecoveryRecord
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class CaptureResponseMapperTest {
    private val mapper = CaptureResponseMapperImpl()
    private val now = Instant.parse("2026-10-15T16:36:52Z")
    private val operation = CaptureOperationRecord(
        id = "operation-1", requestId = "operation-1", fingerprint = "operation-1", step = CaptureNames.EXPORT,
        attemptId = null, state = CaptureNames.SUCCEEDED, startedAt = now, updatedAt = now,
        budget = CaptureBudget(), scanPages = null, walletAttemptId = "wallet-1", marketAttemptId = null, ratesAttemptId = null,
    )

    @Test
    fun `maps an operation saved without recovery settings`() {
        assertNull(mapper.operation(operation).recovery)
    }

    @Test
    fun `maps an operation's recovery settings when present`() {
        val recovery = mapper.operation(operation.copy(recovery = CaptureRecoveryRecord(totalRequestBudget = 400, totalByteBudget = 1024, maxAttempts = 3, attemptsUsed = 1))).recovery

        assertEquals(true, recovery?.enabled)
        assertEquals(400, recovery?.totalRequestBudget)
        assertEquals(1, recovery?.attemptsUsed)
    }
}
