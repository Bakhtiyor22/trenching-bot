package com.trenching.bot.replay

import com.trenching.bot.replay.api.ReplayCommand
import com.trenching.bot.replay.domain.MarketCoverageEndedException
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.DipSampleV5
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReplayCommandTest {
    @TempDir lateinit var directory: Path
    private val output = ByteArrayOutputStream()

    @Test
    fun `a v5 dataset whose coverage ends before an outcome is decided is refused and writes no report`() {
        val dataset = directory.resolve("decided-early.json")
        Files.writeString(dataset, DipSampleV5.json(DipSampleV5.dataset(mapOf(DipSample.pair(DipSample.TAKE_PROFIT) to 1005L))))
        val report = directory.resolve("report.json")

        val refusal = assertFailsWith<MarketCoverageEndedException> {
            ReplayCommand.execute(arrayOf(dataset.toString(), report.toString()), PrintStream(output))
        }

        assertTrue(refusal.message.orEmpty().contains("MARKET_COVERAGE_ENDED"), refusal.message)
        assertTrue(refusal.message.orEmpty().contains("mint=${DipSample.mint(DipSample.TAKE_PROFIT).value} decidedAt=1006 throughSlot=1005"),
            refusal.message)
        assertFalse(Files.exists(report))
        assertEquals("", output.toString())
    }

    @Test
    fun `a v5 dataset covering every outcome replays and writes its report`() {
        val dataset = directory.resolve("full.json")
        Files.writeString(dataset, DipSampleV5.json())
        val report = directory.resolve("report.json")

        ReplayCommand.execute(arrayOf(dataset.toString(), report.toString()), PrintStream(output))

        assertTrue(Files.exists(report))
        assertTrue(output.toString().lines().contains("report=$report"), output.toString())
    }
}
