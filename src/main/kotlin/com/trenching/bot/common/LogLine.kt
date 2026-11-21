package com.trenching.bot.common

import org.slf4j.Logger

/**
 * One parameterized log message: a sentence, then `key=value` for each field that is present. A field whose value is null is
 * left out, so an absent optional value never prints as `=null`.
 */
class LogLine(sentence: String, vararg sentenceArguments: Any) {
    private val format = StringBuilder(sentence)
    private val arguments = sentenceArguments.toMutableList()

    fun field(key: String, value: Any?): LogLine {
        if (value != null) {
            format.append(' ').append(key).append("={}")
            arguments += value
        }
        return this
    }

    fun debug(log: Logger) = log.debug(format.toString(), *arguments.toTypedArray())

    fun info(log: Logger) = log.info(format.toString(), *arguments.toTypedArray())

    fun warn(log: Logger) = log.warn(format.toString(), *arguments.toTypedArray())

    fun error(log: Logger, cause: Throwable) = log.error(format.toString(), *arguments.toTypedArray(), cause)
}
