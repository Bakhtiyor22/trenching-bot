package com.trenching.bot.replay.api

import java.nio.file.Path

/**
 * The optional `--transaction-cache-directory <path>` of the capture commands. It is split off before the command parses
 * its own options, because it configures the RPC the command is built with, not the capture it runs.
 */
internal object TransactionCacheOption {
    const val NAME = "--transaction-cache-directory"

    data class Split(val directory: Path?, val remaining: Array<String>)

    /** The cache directory and the other arguments; Invalid when the option is repeated or has no usable path. */
    fun split(args: Array<String>): CommandArguments<Split> {
        if (args.size % 2 != 0) return CommandArguments.Valid(Split(null, args))
        val pairs = args.asList().chunked(2)
        val cache = pairs.filter { it.first() == NAME }
        if (cache.isEmpty()) return CommandArguments.Valid(Split(null, args))
        val value = cache.singleOrNull()?.last()
        if (value == null || value.isBlank() || value.startsWith("--")) return CommandArguments.Invalid
        val directory = CaptureCommandArguments.path(value) as? CommandArguments.Valid ?: return CommandArguments.Invalid
        return CommandArguments.Valid(Split(directory.value, pairs.filter { it.first() != NAME }.flatten().toTypedArray()))
    }
}
