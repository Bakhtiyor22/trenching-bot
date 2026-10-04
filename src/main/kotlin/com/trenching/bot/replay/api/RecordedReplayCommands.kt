package com.trenching.bot.replay.api

import com.trenching.bot.replay.domain.RecordedReplayExport
import com.trenching.bot.replay.domain.RecordedReplayExportRequest
import java.io.PrintStream

internal class RecordedReplayCommands(
    private val export: (RecordedReplayExportRequest) -> RecordedReplayExport,
    private val output: PrintStream,
    private val errors: PrintStream,
) {
    fun export(args: Array<String>): Int {
        if (args.contentEquals(arrayOf(HELP))) return CaptureCommandExits.help(output, RecordedReplayCommandOptions.USAGE)
        return when (val parsed = parse(args)) {
            CommandArguments.Invalid -> CaptureCommandExits.invalid(errors, RecordedReplayCommandOptions.USAGE)
            is CommandArguments.Valid -> CaptureCommandExits.execute(EXPORT, errors) {
                summarize(export(parsed.value))
                CaptureCommandExits.SUCCESS
            }
        }
    }

    private fun parse(args: Array<String>): CommandArguments<RecordedReplayExportRequest> {
        val options = CaptureCommandArguments.options(args, RecordedReplayCommandOptions.OPTIONS)
        if (options !is CommandArguments.Valid) return CommandArguments.Invalid
        val wallet = CaptureCommandArguments.path(options.value[RecordedReplayCommandOptions.WALLET_CAPTURE_DIRECTORY])
        val market = CaptureCommandArguments.path(options.value[RecordedReplayCommandOptions.MARKET_CAPTURE_DIRECTORY])
        val file = CaptureCommandArguments.path(options.value[RecordedReplayCommandOptions.OUTPUT])
        if (wallet !is CommandArguments.Valid || market !is CommandArguments.Valid || file !is CommandArguments.Valid) return CommandArguments.Invalid
        return CommandArguments.Valid(RecordedReplayExportRequest(wallet.value, market.value, file.value))
    }

    private fun summarize(export: RecordedReplayExport) {
        output.println("output=${export.output}")
        output.println("checksum=${export.checksum}")
        output.println("trackedWallet=${export.trackedWallet.value}")
        output.println("sourceBuys=${export.sourceBuys} windows=${export.windows} transactions=${export.transactions} " +
            "observations=${export.observations} written=${export.written}")
        output.println("replayReady=true schemaVersion=1")
        output.println(RecordedReplayCommandOptions.NOTE)
    }

    private companion object {
        const val HELP = "--help"
        const val EXPORT = "exportRecordedReplay"
    }
}
