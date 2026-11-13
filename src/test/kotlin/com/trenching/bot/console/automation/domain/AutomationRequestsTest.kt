package com.trenching.bot.console.automation.domain

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class AutomationRequestsTest {
    private val itemId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val item24 = itemId.take(24)

    @Test
    fun `an item never retried keeps the request ids it always had`() {
        assertEquals("auto-rates-$item24-1", AutomationRequests.capture(AutomationStep.RATES, itemId, attempt = 1, retries = 0))
        assertEquals("auto-cancel-rates-$item24-2", AutomationRequests.cancel(AutomationStep.RATES, itemId, attempt = 2, retries = 0))
    }

    @Test
    fun `a retried item's request ids carry its retry count`() {
        assertEquals("auto-rates-$item24-r1-1", AutomationRequests.capture(AutomationStep.RATES, itemId, attempt = 1, retries = 1))
        assertEquals("auto-cancel-rates-$item24-r3-2", AutomationRequests.cancel(AutomationStep.RATES, itemId, attempt = 2, retries = 3))
    }

    @Test
    fun `the same item, step, retries and attempt always give the same id and the longest id passes the request id rule`() {
        val longest = AutomationRequests.capture(AutomationStep.CREATE_SESSION, itemId, attempt = 99, retries = 999)

        assertEquals(longest, AutomationRequests.capture(AutomationStep.CREATE_SESSION, itemId, attempt = 99, retries = 999))
        assertEquals("auto-create_session-$item24-r999-99", longest)
        assertTrue(longest.length <= 80)
        assertEquals(longest, AutomationRequests.requireRequestId(longest))
    }

    @Test
    fun `a day retry derives one request id per item from the day's request id`() {
        assertEquals("day-1-$item24", AutomationRequests.dayItem("day-1", itemId))
        assertEquals("d".repeat(55) + "-$item24", AutomationRequests.dayItem("d".repeat(55), itemId), "exactly 80 characters stays readable")
    }

    @Test
    fun `a day request id too long to keep is replaced by its hash, so ids sharing a long prefix stay apart`() {
        val prefix = "r".repeat(55)
        val first = AutomationRequests.dayItem(prefix + "a", itemId)
        val second = AutomationRequests.dayItem(prefix + "b", itemId)

        assertNotEquals(first, second)
        assertEquals(first, AutomationRequests.dayItem(prefix + "a", itemId))
        listOf(first, second, AutomationRequests.dayItem("r".repeat(80), itemId)).forEach {
            assertTrue(it.length <= 80)
            assertTrue(it.endsWith("-$item24"))
            assertEquals(it, AutomationRequests.requireRequestId(it))
        }
        assertNotEquals(AutomationRequests.dayItem(prefix + "a", itemId), AutomationRequests.dayItem(prefix + "a", itemId.reversed()))
    }
}
