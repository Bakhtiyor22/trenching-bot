package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.replay.RecordedWalletFixtures.WALLET
import com.trenching.bot.replay.api.RecordedReplayCommands
import com.trenching.bot.replay.domain.RecordedReplayExport
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RecordedReplayCommandsTest {
    @TempDir lateinit var directory: Path
    private val output = ByteArrayOutputStream()
    private val errors = ByteArrayOutputStream()
    private var exported: RecordedReplayExportRequest? = null

    @Test
    fun `rejects missing unknown repeated and blank arguments before exporting`() {
        val base = arrayOf("--wallet-capture-directory", path("wallet"), "--market-capture-directory", path("markets"),
            "--rates-capture-directory", path("rates"))
        listOf(
            emptyArray(), base, arrayOf("--market-capture-directory", path("markets"), "--output", path("replay.json")),
            arrayOf("--wallet-capture-directory", path("wallet"), "--market-capture-directory", path("markets"), "--output", path("replay.json")),
            base + arrayOf("--output", path("replay.json"), "--output", path("other.json")),
            base + arrayOf("--output", path("replay.json"), "--scan-pages", "3"),
            base + arrayOf("--output", " "), base + arrayOf("--output"),
        ).forEachIndexed { index, args -> assertEquals(2, commands().export(args), "case $index") }
        assertEquals(null, exported)
        assertTrue(errors.toString().contains("category=INVALID_ARGUMENTS"))
        assertTrue(errors.toString().contains("Usage: exportRecordedReplay --wallet-capture-directory <path> --market-capture-directory <path> " +
            "--rates-capture-directory <path> --output <file>"))
    }

    @Test
    fun `forwards the normalized paths and prints the export summary without dataset json`() {
        assertEquals(0, commands().export(arrayOf("--wallet-capture-directory", path("wallet"),
            "--market-capture-directory", path("markets"), "--rates-capture-directory", path("rates"), "--output", path("out/../replay.json"))))

        assertEquals(RecordedReplayExportRequest(resolved("wallet"), resolved("markets"), resolved("rates"), resolved("replay.json")), exported)
        val lines = output.toString().lines()
        assertTrue(lines.contains("output=${resolved("replay.json")}"), output.toString())
        assertTrue(lines.contains("checksum=$CHECKSUM"))
        assertTrue(lines.contains("trackedWallet=$WALLET"))
        assertTrue(lines.contains("sourceBuys=1 windows=1 transactions=1 observations=17 written=true"))
        assertTrue(lines.contains("replayReady=true schemaVersion=6"))
        assertTrue(lines.any { it.startsWith("note=") && it.contains("modelled paper") })
        assertFalse(output.toString().contains("{"))
        assertEquals("", errors.toString())
    }

    @Test
    fun `a refused export prints only its operation category and reason`() {
        val commands = RecordedReplayCommands({ throw CaptureValidationException("MARKET_CAPTURE_INCOMPLETE") }, PrintStream(output), PrintStream(errors))

        assertEquals(1, commands.export(arrayOf("--wallet-capture-directory", path("wallet"),
            "--market-capture-directory", path("markets"), "--rates-capture-directory", path("rates"), "--output", path("replay.json"))))

        assertEquals(listOf("operation=exportRecordedReplay category=ARTIFACT_OR_CONFIGURATION_INVALID", "reason=MARKET_CAPTURE_INCOMPLETE"),
            errors.toString().lines().filter { it.isNotBlank() })
        assertEquals("", output.toString())
    }

    @Test
    fun `help prints usage without exporting`() {
        assertEquals(0, commands().export(arrayOf("--help")))
        assertTrue(output.toString().contains("Usage: exportRecordedReplay"))
        assertEquals(null, exported)
    }

    private fun commands() = RecordedReplayCommands({ request ->
        exported = request
        RecordedReplayExport(request.output, CHECKSUM, WalletAddress.of(WALLET), sourceBuys = 1, windows = 1, transactions = 1,
            observations = 17, written = true)
    }, PrintStream(output), PrintStream(errors))

    private fun path(name: String): String = directory.resolve(name).toString()

    private fun resolved(name: String): Path = directory.resolve(name).toAbsolutePath().normalize()

    private companion object {
        const val CHECKSUM = "5f2b0c1d8e7a6f4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b"
    }
}
