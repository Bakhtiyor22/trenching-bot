package com.trenching.bot.replay.api

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.walletactivity.WalletHistoryCapture
import com.trenching.bot.walletactivity.WalletHistoryCaptureResult
import com.trenching.bot.walletactivity.WalletHistoryDecodeOutcome
import com.trenching.bot.walletactivity.WalletHistoryDecodeResult
import com.trenching.bot.walletactivity.WalletHistoryOutcome
import java.io.PrintStream
import java.nio.file.Path

internal class WalletHistoryCommands(
    private val captureFactory: () -> WalletHistoryCapture,
    private val inspect: (Path) -> WalletHistoryCaptureResult,
    private val decode: (Path, WalletAddress) -> WalletHistoryDecodeResult,
    private val output: PrintStream,
    private val errors: PrintStream,
) {
    fun capture(args: Array<String>): Int {
        if (args.contentEquals(arrayOf("--help"))) return CaptureCommandExits.help(output, WalletHistoryCommandOptions.CAPTURE_USAGE)
        return when (val parsed = WalletHistoryArgumentParser.capture(args)) {
            CommandArguments.Invalid -> CaptureCommandExits.invalid(errors, WalletHistoryCommandOptions.CAPTURE_USAGE)
            is CommandArguments.Valid -> CaptureCommandExits.execute(CAPTURE, errors) {
                val result = captureFactory().capture(parsed.value)
                summarize(result)
                status(WalletHistoryCommandOptions.DECODER_NOT_RUN)
                candidates(result)
                if (result.manifest.complete) CaptureCommandExits.SUCCESS else CaptureCommandExits.incomplete(CAPTURE, errors)
            }
        }
    }

    fun inspect(args: Array<String>): Int {
        if (args.contentEquals(arrayOf("--help"))) return CaptureCommandExits.help(output, WalletHistoryCommandOptions.INSPECT_USAGE)
        return when (val parsed = WalletHistoryArgumentParser.inspect(args)) {
            CommandArguments.Invalid -> CaptureCommandExits.invalid(errors, WalletHistoryCommandOptions.INSPECT_USAGE)
            is CommandArguments.Valid -> CaptureCommandExits.execute(INSPECT, errors) {
                val result = inspect(parsed.value)
                summarize(result)
                if (result.manifest.complete) {
                    decoded(decode(parsed.value, WalletAddress.of(result.manifest.wallet)))
                } else status(WalletHistoryCommandOptions.DECODER_CAPTURE_INCOMPLETE)
                candidates(result)
                CaptureCommandExits.SUCCESS
            }
        }
    }

    private fun summarize(result: WalletHistoryCaptureResult) {
        val manifest = result.manifest
        output.println("directory=${result.directory}")
        output.println("manifest=${result.directory.resolve("manifest.json")}")
        output.println("scope=LATEST_SUCCESSFUL_WALLET_REFERENCES wallet=${manifest.wallet} commitment=${manifest.commitment}")
        output.println("selected=${manifest.selection.size} complete=${manifest.complete} discoveryComplete=${manifest.discoveryComplete}")
        output.println("scanned=${manifest.scannedSignatures} skippedFailed=${manifest.skippedFailed} scanPages=${manifest.scanPageLimit} " +
            "discoveryStop=${manifest.discoveryStop ?: WalletHistoryCommandOptions.NO_DISCOVERY_STOP}")
        output.println("requestsUsed=${manifest.requestsUsed} bytesUsed=${manifest.bytesUsed}")
        WalletHistoryOutcome.entries.forEach { outcome ->
            output.println("$outcome=${manifest.records.count { it.outcome == outcome }}")
        }
    }

    private fun decoded(result: WalletHistoryDecodeResult) {
        status(result.decoder.name)
        WalletHistoryDecodeOutcome.entries.forEach { outcome ->
            output.println("decode.$outcome=${result.records.count { it.outcome == outcome }}")
        }
        result.transactions.take(WalletHistoryCommandOptions.DECODED_TRADE_LIMIT).forEach { trade ->
            output.println("decodedTrade=${trade.signature.value} slot=${trade.slot.value} mint=${trade.pair.base.value} " +
                "baseRaw=${trade.baseDelta.abs()} baseDecimals=${trade.baseDecimals} " +
                "quoteRaw=${trade.quoteDelta.abs()} quoteDecimals=${trade.quoteDecimals} instructionIndex=${trade.instructionIndex}")
        }
    }

    private fun status(decoderStatus: String) {
        output.println("replayReady=false decoderStatus=$decoderStatus")
    }

    private fun candidates(result: WalletHistoryCaptureResult) {
        result.manifest.records.asSequence()
            .filter { it.outcome == WalletHistoryOutcome.UNCLASSIFIED && it.artifact != null }
            .take(WalletHistoryCommandOptions.CANDIDATE_LIMIT)
            .forEach { output.println("candidateSignature=${it.signature} slot=${it.slot} artifact=${it.artifact}") }
    }

    private companion object {
        const val CAPTURE = "captureWalletHistory"
        const val INSPECT = "inspectWalletHistory"
    }
}
