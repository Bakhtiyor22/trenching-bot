package com.trenching.bot.replay.api

import com.trenching.bot.marketdata.ReferenceRateCapture
import com.trenching.bot.marketdata.ReferenceRateCaptureRequest
import com.trenching.bot.marketdata.ReferenceRateCaptureResult
import java.io.PrintStream
import java.nio.file.Path

/** `captureReferenceRates` (user-run, calls Binance) and `inspectReferenceRates` (offline). */
internal class ReferenceRateCommands(
    private val captureFactory: () -> ReferenceRateCapture,
    private val inspect: (Path) -> ReferenceRateCaptureResult,
    private val output: PrintStream,
    private val errors: PrintStream,
) {
    fun capture(args: Array<String>): Int {
        if (args.contentEquals(arrayOf(HELP))) return CaptureCommandExits.help(output, CAPTURE_USAGE)
        val options = CaptureCommandArguments.options(args, setOf(MARKET_CAPTURE_DIRECTORY, DIRECTORY))
        if (options !is CommandArguments.Valid) return CaptureCommandExits.invalid(errors, CAPTURE_USAGE)
        val market = CaptureCommandArguments.path(options.value[MARKET_CAPTURE_DIRECTORY])
        val directory = CaptureCommandArguments.path(options.value[DIRECTORY])
        if (market !is CommandArguments.Valid || directory !is CommandArguments.Valid) return CaptureCommandExits.invalid(errors, CAPTURE_USAGE)
        return CaptureCommandExits.execute(CAPTURE, errors) {
            val result = captureFactory().capture(ReferenceRateCaptureRequest(market.value, directory.value))
            summarize(result)
            if (result.manifest.complete) CaptureCommandExits.SUCCESS else CaptureCommandExits.incomplete(CAPTURE, errors)
        }
    }

    fun inspect(args: Array<String>): Int {
        if (args.contentEquals(arrayOf(HELP))) return CaptureCommandExits.help(output, INSPECT_USAGE)
        val options = CaptureCommandArguments.options(args, setOf(DIRECTORY))
        if (options !is CommandArguments.Valid) return CaptureCommandExits.invalid(errors, INSPECT_USAGE)
        val directory = CaptureCommandArguments.path(options.value[DIRECTORY])
        if (directory !is CommandArguments.Valid) return CaptureCommandExits.invalid(errors, INSPECT_USAGE)
        return CaptureCommandExits.execute(INSPECT, errors) {
            summarize(inspect(directory.value))
            CaptureCommandExits.SUCCESS
        }
    }

    private fun summarize(result: ReferenceRateCaptureResult) {
        val records = result.manifest.records
        output.println("directory=${result.directory}")
        output.println("minutes=${records.size} withRate=${records.count { it.rate != null }} complete=${result.manifest.complete}")
        records.mapNotNull { it.unavailableReason }.groupingBy { it }.eachCount().toSortedMap()
            .forEach { (reason, count) -> output.println("unavailable.$reason=$count") }
        listOfNotNull(records.firstOrNull(), records.lastOrNull()).distinct().forEach { record ->
            output.println("rate minute=${record.minute} rate=${record.rate ?: "-"} source=${record.source ?: "-"}")
        }
    }

    private companion object {
        const val HELP = "--help"
        const val CAPTURE = "captureReferenceRates"
        const val INSPECT = "inspectReferenceRates"
        const val MARKET_CAPTURE_DIRECTORY = "--market-capture-directory"
        const val DIRECTORY = "--directory"
        const val CAPTURE_USAGE = "Usage: captureReferenceRates --market-capture-directory <path> --directory <path>"
        const val INSPECT_USAGE = "Usage: inspectReferenceRates --directory <path>"
    }
}
