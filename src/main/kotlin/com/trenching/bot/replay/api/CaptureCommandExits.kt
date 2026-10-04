package com.trenching.bot.replay.api

import com.trenching.bot.chaincapture.CaptureBudgetException
import com.trenching.bot.chaincapture.CaptureProviderException
import com.trenching.bot.chaincapture.CaptureValidationException
import java.io.IOException
import java.io.PrintStream

/**
 * Exit codes and sanitized failure categories shared by the capture commands. Exception text is never printed; a validation
 * failure adds its fixed reason code.
 */
internal object CaptureCommandExits {
    const val SUCCESS = 0
    const val FAILURE = 1
    const val INVALID_ARGUMENTS = 2
    const val NOT_CONFIGURED = "NOT_CONFIGURED"

    fun execute(operation: String, errors: PrintStream, action: () -> Int): Int = try {
        action()
    } catch (exception: Exception) {
        val category = when (exception) {
            is CaptureProviderException -> if (exception.category == NOT_CONFIGURED) NOT_CONFIGURED else "PROVIDER_FAILURE"
            is CaptureBudgetException -> "BUDGET_EXHAUSTED"
            is CaptureValidationException -> "ARTIFACT_OR_CONFIGURATION_INVALID"
            is IOException -> "ARTIFACT_IO_FAILURE"
            else -> "UNEXPECTED_FAILURE"
        }
        errors.println("operation=$operation category=$category")
        if (exception is CaptureValidationException) errors.println("reason=${exception.category}")
        if (category == NOT_CONFIGURED) errors.println("Set SOLANA_RPC_URL in the environment of this command process.")
        FAILURE
    }

    fun incomplete(operation: String, errors: PrintStream): Int {
        errors.println("operation=$operation category=CAPTURE_INCOMPLETE")
        return FAILURE
    }

    fun invalid(errors: PrintStream, usage: String): Int {
        errors.println("category=INVALID_ARGUMENTS")
        errors.println(usage)
        return INVALID_ARGUMENTS
    }

    fun help(output: PrintStream, usage: String): Int {
        output.println(usage)
        return SUCCESS
    }
}
