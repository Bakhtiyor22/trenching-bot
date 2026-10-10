package com.trenching.bot.replay

import com.trenching.bot.chaincapture.CaptureValidationException
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.marketdata.MarketObservationExtractorVersion
import com.trenching.bot.replay.RecordedWalletFixtures.WALLET
import com.trenching.bot.replay.api.ExecutionCalibrationCommands
import com.trenching.bot.replay.domain.CalibrationReport
import com.trenching.bot.replay.domain.CalibrationSummary
import com.trenching.bot.replay.domain.ExecutionCalibrationRequest
import com.trenching.bot.walletactivity.WalletHistoryDecoderVersion
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExecutionCalibrationCommandsTest {
    @TempDir lateinit var directory: Path
    private val output = ByteArrayOutputStream()
    private val errors = ByteArrayOutputStream()
    private var calibrated: ExecutionCalibrationRequest? = null
    private var saved: Path? = null

    @Test
    fun `rejects missing unknown repeated and blank arguments before calibrating`() {
        val base = arrayOf("--wallet-capture-directory", path("wallet"), "--market-capture-directory", path("markets"))
        listOf(
            emptyArray(), base,
            arrayOf("--market-capture-directory", path("markets"), "--report", path("report.json")),
            arrayOf("--wallet-capture-directory", path("wallet"), "--report", path("report.json")),
            base + arrayOf("--report", path("report.json"), "--report", path("other.json")),
            base + arrayOf("--report", path("report.json"), "--output", path("other.json")),
            base + arrayOf("--report", " "), base + arrayOf("--report"),
        ).forEachIndexed { index, args -> assertEquals(2, commands(SUMMARY).calibrate(args), "case $index") }
        assertNull(calibrated)
        assertNull(saved)
        assertTrue(errors.toString().contains("category=INVALID_ARGUMENTS"))
        assertTrue(errors.toString().contains(
            "Usage: calibrateExecution --wallet-capture-directory <path> --market-capture-directory <path> --report <file>"))
    }

    @Test
    fun `forwards the normalized paths, saves the report and prints one summary line`() {
        assertEquals(0, commands(SUMMARY).calibrate(arrayOf("--wallet-capture-directory", path("wallet"),
            "--market-capture-directory", path("markets"), "--report", path("out/../report.json"))))

        assertEquals(ExecutionCalibrationRequest(resolved("wallet"), resolved("markets"), resolved("report.json")), calibrated)
        assertEquals(resolved("report.json"), saved)
        assertEquals(listOf("buys=7 mathExact=7 mathMismatches=0 latencyChecked=5 noPriorState=2 medianErrorBps=0.50 " +
            "maxErrorBps=12.25 medianTxFeeLamports=5005000 medianOtherLamports=1000000"), output.toString().lines().filter { it.isNotBlank() })
        assertEquals("", errors.toString())
    }

    @Test
    fun `values without a contributing buy print as none`() {
        val empty = SUMMARY.copy(latencyChecked = 0, noPriorState = 7, medianErrorBps = null, maxErrorBps = null,
            medianTransactionFeeLamports = null, medianOtherLamports = null)

        assertEquals(0, commands(empty).calibrate(arrayOf("--wallet-capture-directory", path("wallet"),
            "--market-capture-directory", path("markets"), "--report", path("report.json"))))

        assertEquals("buys=7 mathExact=7 mathMismatches=0 latencyChecked=0 noPriorState=7 medianErrorBps=none maxErrorBps=none " +
            "medianTxFeeLamports=none medianOtherLamports=none", output.toString().trim())
    }

    @Test
    fun `a refused calibration prints only its operation category and reason and saves nothing`() {
        val commands = ExecutionCalibrationCommands({ throw CaptureValidationException("MARKET_CAPTURE_MISMATCH") },
            { target, _ -> saved = target }, PrintStream(output), PrintStream(errors))

        assertEquals(1, commands.calibrate(arrayOf("--wallet-capture-directory", path("wallet"),
            "--market-capture-directory", path("markets"), "--report", path("report.json"))))

        assertEquals(listOf("operation=calibrateExecution category=ARTIFACT_OR_CONFIGURATION_INVALID", "reason=MARKET_CAPTURE_MISMATCH"),
            errors.toString().lines().filter { it.isNotBlank() })
        assertEquals("", output.toString())
        assertNull(saved)
    }

    @Test
    fun `help prints usage without calibrating`() {
        assertEquals(0, commands(SUMMARY).calibrate(arrayOf("--help")))
        assertTrue(output.toString().contains("Usage: calibrateExecution"))
        assertNull(calibrated)
    }

    @Test
    fun `fractional lamport medians print exactly without exponent notation`() {
        val summary = SUMMARY.copy(medianTransactionFeeLamports = BigDecimal("5000.5"), medianOtherLamports = BigDecimal("-1.5"))

        assertEquals(0, commands(summary).calibrate(arrayOf("--wallet-capture-directory", path("wallet"),
            "--market-capture-directory", path("markets"), "--report", path("report.json"))))

        assertTrue(output.toString().contains("medianTxFeeLamports=5000.5 medianOtherLamports=-1.5"))
    }
    private fun commands(summary: CalibrationSummary) = ExecutionCalibrationCommands(
        { request ->
            calibrated = request
            CalibrationReport(WalletAddress.of(WALLET), WalletHistoryDecoderVersion.PUMPFUN_BONDING_CURVE_BUY_V1,
                MarketObservationExtractorVersion.PUMPFUN_BONDING_CURVE_TRADE_V1, summary, emptyList())
        },
        { target, _ -> saved = target },
        PrintStream(output),
        PrintStream(errors),
    )

    private fun path(name: String): String = directory.resolve(name).toString()

    private fun resolved(name: String): Path = directory.resolve(name).toAbsolutePath().normalize()

    private companion object {
        val SUMMARY = CalibrationSummary(
            buys = 7, mathExact = 7, mathMismatches = 0, latencyChecked = 5, noPriorState = 2, unpredictable = 0,
            medianErrorBps = BigDecimal("0.50"), maxErrorBps = BigDecimal("12.25"),
            medianTransactionFeeLamports = BigDecimal("5005000").stripTrailingZeros(), medianOtherLamports = BigDecimal("1000000").stripTrailingZeros(),
        )
    }
}
