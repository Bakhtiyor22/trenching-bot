package com.trenching.bot.replay.api

import com.trenching.bot.replay.domain.CalibrationReport
import com.trenching.bot.replay.domain.CalibrationSummary
import com.trenching.bot.replay.domain.ExecutionCalibrationRequest
import java.io.PrintStream
import java.nio.file.Path

internal class ExecutionCalibrationCommands(
    private val calibrate: (ExecutionCalibrationRequest) -> CalibrationReport,
    private val save: (Path, CalibrationReport) -> Unit,
    private val output: PrintStream,
    private val errors: PrintStream,
) {
    fun calibrate(args: Array<String>): Int {
        if (args.contentEquals(arrayOf(HELP))) return CaptureCommandExits.help(output, ExecutionCalibrationCommandOptions.USAGE)
        return when (val parsed = parse(args)) {
            CommandArguments.Invalid -> CaptureCommandExits.invalid(errors, ExecutionCalibrationCommandOptions.USAGE)
            is CommandArguments.Valid -> CaptureCommandExits.execute(CALIBRATE, errors) {
                val report = calibrate(parsed.value)
                save(parsed.value.report, report)
                output.println(line(report.summary))
                CaptureCommandExits.SUCCESS
            }
        }
    }

    private fun parse(args: Array<String>): CommandArguments<ExecutionCalibrationRequest> {
        val options = CaptureCommandArguments.options(args, ExecutionCalibrationCommandOptions.OPTIONS)
        if (options !is CommandArguments.Valid) return CommandArguments.Invalid
        val wallet = CaptureCommandArguments.path(options.value[ExecutionCalibrationCommandOptions.WALLET_CAPTURE_DIRECTORY])
        val market = CaptureCommandArguments.path(options.value[ExecutionCalibrationCommandOptions.MARKET_CAPTURE_DIRECTORY])
        val report = CaptureCommandArguments.path(options.value[ExecutionCalibrationCommandOptions.REPORT])
        if (wallet !is CommandArguments.Valid || market !is CommandArguments.Valid || report !is CommandArguments.Valid) {
            return CommandArguments.Invalid
        }
        return CommandArguments.Valid(ExecutionCalibrationRequest(wallet.value, market.value, report.value))
    }

    private fun line(summary: CalibrationSummary): String =
        "buys=${summary.buys} mathExact=${summary.mathExact} mathMismatches=${summary.mathMismatches} " +
            "latencyChecked=${summary.latencyChecked} noPriorState=${summary.noPriorState} " +
            "medianErrorBps=${text(summary.medianErrorBps?.toPlainString())} maxErrorBps=${text(summary.maxErrorBps?.toPlainString())} " +
            "medianTxFeeLamports=${text(summary.medianTransactionFeeLamports?.toPlainString())} " +
            "medianOtherLamports=${text(summary.medianOtherLamports?.toPlainString())}"

    private fun text(value: String?): String = value ?: ExecutionCalibrationCommandOptions.NONE

    private companion object {
        const val HELP = "--help"
        const val CALIBRATE = "calibrateExecution"
    }
}
