package com.trenching.bot.replay.api

import com.trenching.bot.marketdata.MarketHistoryCaptureResult
import com.trenching.bot.marketdata.MarketHistoryDecodeResult
import com.trenching.bot.marketdata.MarketTransactionOutcome
import com.trenching.bot.replay.domain.ReplayMarketCaptureRequest
import java.io.PrintStream
import java.nio.file.Path

internal class ReplayMarketCommands(
    private val capture: (ReplayMarketCaptureRequest) -> MarketHistoryCaptureResult,
    private val inspect: (Path) -> MarketHistoryCaptureResult,
    private val decode: (Path) -> MarketHistoryDecodeResult,
    private val output: PrintStream,
    private val errors: PrintStream,
) {
    fun capture(args: Array<String>): Int {
        if (args.contentEquals(arrayOf(HELP))) return CaptureCommandExits.help(output, ReplayMarketCommandOptions.CAPTURE_USAGE)
        return when (val parsed = ReplayMarketArgumentParser.capture(args)) {
            CommandArguments.Invalid -> CaptureCommandExits.invalid(errors, ReplayMarketCommandOptions.CAPTURE_USAGE)
            is CommandArguments.Valid -> CaptureCommandExits.execute(CAPTURE, errors) {
                val result = capture(parsed.value)
                summarize(result)
                status(ReplayMarketCommandOptions.EXTRACTOR_NOT_RUN)
                if (result.manifest.complete) CaptureCommandExits.SUCCESS else CaptureCommandExits.incomplete(CAPTURE, errors)
            }
        }
    }

    fun inspect(args: Array<String>): Int {
        if (args.contentEquals(arrayOf(HELP))) return CaptureCommandExits.help(output, ReplayMarketCommandOptions.INSPECT_USAGE)
        return when (val parsed = ReplayMarketArgumentParser.inspect(args)) {
            CommandArguments.Invalid -> CaptureCommandExits.invalid(errors, ReplayMarketCommandOptions.INSPECT_USAGE)
            is CommandArguments.Valid -> CaptureCommandExits.execute(INSPECT, errors) {
                val result = inspect(parsed.value)
                summarize(result)
                if (result.manifest.acquisitionComplete) decoded(decode(parsed.value))
                else status(ReplayMarketCommandOptions.EXTRACTOR_CAPTURE_INCOMPLETE)
                CaptureCommandExits.SUCCESS
            }
        }
    }

    private fun summarize(result: MarketHistoryCaptureResult) {
        val manifest = result.manifest
        val parameters = manifest.windowParameters
        output.println("directory=${result.directory}")
        output.println("manifest=${result.manifestFile}")
        output.println("scope=MINT_REFERENCED_PUMPFUN_BONDING_CURVE_TRADES commitment=${manifest.commitment}")
        output.println("entryOffsets=${parameters.firstEntryOffset}..${parameters.lastEntryOffset} maxWaitSlots=${parameters.maxWaitSlots} " +
            "maxAgeSlots=${parameters.maxAgeSlots} holdingSlots=${parameters.holdingSlots}")
        output.println("windows=${manifest.windows.size} scanPages=${manifest.scanPageLimit} maxTransactions=${manifest.maxTransactions} " +
            "acquisitionComplete=${manifest.acquisitionComplete} complete=${manifest.complete}")
        manifest.windows.forEach { window ->
            output.println("window=${window.baseMint} fromSlot=${window.fromSlot} toSlot=${window.toSlot} coverage=${window.coverage} " +
                "scanned=${window.scanned} skippedFailed=${window.skippedFailed} skippedNewer=${window.skippedNewer} selected=${window.selected}")
        }
        output.println("requestsUsed=${manifest.requestsUsed} bytesUsed=${manifest.bytesUsed}")
        MarketTransactionOutcome.entries.forEach { outcome ->
            output.println("transaction.$outcome=${manifest.records.count { it.outcome == outcome }}")
        }
    }

    private fun decoded(result: MarketHistoryDecodeResult) {
        status(result.extractor.name)
        output.println("observations=${result.observations.size}")
        result.windows.forEach { window ->
            output.println("windowYield=${window.window.baseMint.value} fromSlot=${window.window.fromSlot.value} " +
                "toSlot=${window.window.toSlot.value} yieldsObservations=${window.yieldsObservations} " +
                "detailGaps=${window.detailGaps} observations=${window.observations}")
        }
        result.exclusions.forEach { (reason, count) -> output.println("exclusion.$reason=$count") }
        result.observations.take(ReplayMarketCommandOptions.OBSERVATION_LIMIT).forEach { observation ->
            output.println("observation=${observation.id} slot=${observation.slot.value} price=${observation.price.toPlainString()}")
        }
    }

    private fun status(extractorStatus: String) {
        output.println("replayReady=false extractorStatus=$extractorStatus")
    }

    private companion object {
        const val HELP = "--help"
        const val CAPTURE = "captureReplayMarkets"
        const val INSPECT = "inspectReplayMarkets"
    }
}
