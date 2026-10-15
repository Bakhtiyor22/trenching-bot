package com.trenching.bot.chaincapture

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DetailBatchesTest {
    private val batches = DetailBatches(BoundedParallelFetch(), CaptureCheckpointObserver.NONE, 1, 2, "test_capture")

    @Test
    fun `without an until condition every batch is fetched applied and checkpointed`() {
        val checkpoints = mutableListOf<List<Int>>()

        val state = run(items = (1..5).toList(), checkpoints = checkpoints)

        assertEquals((1..5).toList(), state)
        assertEquals(listOf(listOf(1, 2), listOf(1, 2, 3, 4), listOf(1, 2, 3, 4, 5)), checkpoints)
    }

    @Test
    fun `an until condition met after a checkpoint ends the phase without a stop reason or another request`() {
        val checkpoints = mutableListOf<List<Int>>()
        val fetched = mutableListOf<Int>()
        val stop = CaptureStop()

        val state = run(items = (1..7).toList(), checkpoints = checkpoints, fetched = fetched, stop = stop) { applied -> 3 in applied }

        assertEquals(listOf(1, 2, 3, 4), state)
        assertEquals(listOf(1, 2, 3, 4), fetched)
        assertEquals(listOf(listOf(1, 2), listOf(1, 2, 3, 4)), checkpoints)
        assertNull(stop.reason)
    }

    private fun run(
        items: List<Int>,
        checkpoints: MutableList<List<Int>>,
        fetched: MutableList<Int> = mutableListOf(),
        stop: CaptureStop = CaptureStop(),
        until: ((List<Int>) -> Boolean)? = null,
    ): List<Int> {
        val budget = CaptureBudget(100, 1_000_000)
        val fetchOne: (Int) -> Int = { item -> fetched += item; item }
        val apply: (List<Int>, DetailBatch<Int, Int>) -> List<Int> = { state, batch -> state + batch.completed.map { it.result } }
        val checkpoint: (List<Int>) -> Unit = { checkpoints += it }
        return if (until == null) {
            batches.run(items, emptyList(), budget, stop, Int::toString, fetchOne, apply, checkpoint, report = {})
        } else {
            batches.run(items, emptyList(), budget, stop, Int::toString, fetchOne, apply, checkpoint, report = {}, until = until)
        }
    }
}
