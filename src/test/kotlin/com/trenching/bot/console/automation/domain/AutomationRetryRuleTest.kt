package com.trenching.bot.console.automation.domain

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class AutomationRetryRuleTest {
    private fun progress(state: AutomationItemState, failureCode: String? = "RATES_INCOMPLETE") =
        AutomationItemProgress(state, AutomationStep.RATES, 2, "capture", null, null, null, failureCode, null, null)

    private val failed = progress(AutomationItemState.FAILED)

    @Test
    fun `a failed live item can be retried while capture is on`() {
        assertNull(AutomationRetryRule.refusal(failed, null, null, captureEnabled = true))
    }

    @Test
    fun `every state but failed is refused as not failed`() {
        (AutomationItemState.entries - AutomationItemState.FAILED).forEach { state ->
            assertEquals(AutomationRetryRefusal.ITEM_NOT_FAILED, AutomationRetryRule.refusal(progress(state), null, null, captureEnabled = true), state.name)
        }
    }

    @Test
    fun `a backfill budget stop and a missing block time cannot be retried`() {
        listOf(AutomationFailures.BACKFILL_BUDGET, AutomationFailures.MISSING_BLOCK_TIME).forEach { code ->
            assertEquals(AutomationRetryRefusal.RETRY_NOT_ALLOWED, AutomationRetryRule.refusal(progress(AutomationItemState.FAILED, code), null, null, true), code)
        }
        listOf(AutomationFailures.CAPTURE_NOT_FOUND, AutomationFailures.SWEEP_FAILED).forEach { code ->
            assertNull(AutomationRetryRule.refusal(progress(AutomationItemState.FAILED, code), null, null, true), code)
        }
    }

    @Test
    fun `a backfill's item is refused only while its backfill is queued or scanning`() {
        BackfillState.entries.forEach { state ->
            val expected = if (state in BackfillState.SCANNING_STATES) AutomationRetryRefusal.BACKFILL_NOT_CAPTURING else null
            assertEquals(expected, AutomationRetryRule.refusal(failed, "bf-1", state, captureEnabled = true), state.name)
        }
        assertEquals(AutomationRetryRefusal.BACKFILL_NOT_CAPTURING, AutomationRetryRule.refusal(failed, "bf-1", null, captureEnabled = true))
    }

    @Test
    fun `with capture off every retry is refused, after the item's own refusals`() {
        assertEquals(AutomationRetryRefusal.CAPTURE_DISABLED, AutomationRetryRule.refusal(failed, null, null, captureEnabled = false))
        assertEquals(AutomationRetryRefusal.ITEM_NOT_FAILED, AutomationRetryRule.refusal(progress(AutomationItemState.CAPTURING), null, null, captureEnabled = false))
    }
}
