package com.trenching.bot.replay.api

import java.nio.file.InvalidPathException
import java.nio.file.Path

/** `--option value` pairs shared by the capture commands: each allowed option at most once, no blank or option-like values. */
internal object CaptureCommandArguments {
    fun options(args: Array<String>, allowed: Set<String>): CommandArguments<Map<String, String>> {
        if (args.isEmpty() || args.size % 2 != 0) return CommandArguments.Invalid
        val values = linkedMapOf<String, String>()
        args.asList().chunked(2).forEach { (option, value) ->
            if (option !in allowed || option in values || value.isBlank() || value.startsWith("--")) {
                return CommandArguments.Invalid
            }
            values[option] = value
        }
        return CommandArguments.Valid(values)
    }

    /** An absolute, normalized directory or file path. */
    fun path(value: String?): CommandArguments<Path> {
        if (value.isNullOrBlank()) return CommandArguments.Invalid
        return try { CommandArguments.Valid(Path.of(value).toAbsolutePath().normalize()) }
        catch (_: InvalidPathException) { CommandArguments.Invalid }
    }

    /** The option's value within [range], [default] when absent, or null when present but not a number in range. */
    fun integer(values: Map<String, String>, option: String, default: Int, range: IntRange): Int? {
        val value = if (option in values) values[option]?.toIntOrNull() else default
        return value?.takeIf { it in range }
    }

    fun long(values: Map<String, String>, option: String, default: Long, range: LongRange): Long? {
        val value = if (option in values) values[option]?.toLongOrNull() else default
        return value?.takeIf { it in range }
    }
}
