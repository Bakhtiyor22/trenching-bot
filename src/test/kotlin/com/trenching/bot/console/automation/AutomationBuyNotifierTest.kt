package com.trenching.bot.console.automation

import ch.qos.logback.classic.Level
import com.trenching.bot.console.automation.AutomationRows.item
import com.trenching.bot.console.automation.application.AutomationBuyNotifier
import com.trenching.bot.console.automation.application.AutomationVariationViews
import com.trenching.bot.console.automation.domain.AutomationItemState
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.domain.Variation
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunEntity
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.automation.mapper.AutomationVariationMapper
import com.trenching.bot.notification.NotificationPublisher
import com.trenching.bot.portfolio.domain.PositionState
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

/** What the buy notifier reads, and what it does when the message cannot be built or the publish fails; the stored rows are in [AutomationNotificationsDatabaseTest]. */
class AutomationBuyNotifierTest {
    private var sweepRows = emptyList<AutomationSweepRunEntity>()
    private var readFailure: RuntimeException? = null
    private val sweepRuns = mock(AutomationSweepRunRepository::class.java) { call ->
        readFailure?.let { throw it }
        if (call.method.name == "findAllByItemIdOrderByPlanIdAscLabelAsc") sweepRows else null
    }
    private val variationMapper = AutomationVariationMapper()
    private val variations = mock(AutomationVariationRepository::class.java) { call ->
        if (call.method.name == "findAllByPlanIdOrderBySeqAsc") Variation.seed(SweepPlans.DECU_SWEEP_1, EVENING).map { variationMapper.toEntity(it) } else null
    }
    private val published = mutableListOf<String>()
    private var publishFailure: RuntimeException? = null
    private val publisher = NotificationPublisher { notification ->
        publishFailure?.let { throw it }
        published += notification.key
    }
    private val notifier = AutomationBuyNotifier(sweepRuns, AutomationVariationViews(variations, variationMapper), publisher, AutomationFixtures.properties())
    private val logs = LogCapture()

    @AfterTest
    fun detachLog() = logs.close()

    @Test
    fun `a backfilled buy returns before any read`() {
        notifier.publishResult(buy(backfillId = "backfill-1"))

        verifyNoInteractions(sweepRuns, variations)
        assertEquals(emptyList(), published)
    }

    @Test
    fun `a message that cannot be built is logged with the item and skipped, and nothing is raised`() {
        rows(sweepRow(label = " "))

        notifier.publishResult(buy())

        assertEquals(emptyList(), published)
        val errors = logs.at(Level.ERROR)
        assertEquals(1, errors.size)
        assertTrue(errors.single().formattedMessage.contains("itemId=item-1"), errors.single().formattedMessage)
    }

    @Test
    fun `a failure of the publish itself is not caught`() {
        rows(sweepRow(label = "V00"))
        publishFailure = IllegalStateException("outbox is down")

        assertFailsWith<IllegalStateException> { notifier.publishResult(buy()) }
        assertEquals(emptyList(), logs.at(Level.ERROR))
    }

    @Test
    fun `a failure of a read is not caught`() {
        readFailure = IllegalStateException("database is down")

        assertFailsWith<IllegalStateException> { notifier.publishResult(buy()) }
    }

    private fun rows(vararg rows: AutomationSweepRunEntity) {
        sweepRows = rows.toList()
    }

    private fun buy(backfillId: String? = null) =
        item(1, "session-1", AutomationItemState.SIMULATED, blockTime = EVENING).apply { this.backfillId = backfillId }

    private fun sweepRow(label: String) = AutomationSweepRunEntity(
        "row-$label", "item-1", "session-1", "decu-sweep-1", label, "run-1", "hash", "dip-paper-v6", "TAKE_PROFIT", PositionState.CLOSED, true,
        BigDecimal("0.01"), BigDecimal("0.01"), EVENING,
    )

    private companion object {
        val EVENING: Instant = AutomationFixtures.EVENING
    }
}
