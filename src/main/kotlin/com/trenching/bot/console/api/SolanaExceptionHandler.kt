package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.ConsoleErrorResponse
import com.trenching.bot.console.mapper.ConsoleErrorMapper
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.solana.InvalidSolanaAddressException
import com.trenching.bot.console.solana.SolanaNotConfiguredException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [SolanaController::class])
@ConditionalOnConsoleEnabled
class SolanaExceptionHandler(private val errorMapper: ConsoleErrorMapper) {
    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidAddress(e: InvalidSolanaAddressException): ConsoleErrorResponse =
        errorMapper.toResponse(INVALID_ADDRESS, INVALID_ADDRESS_MESSAGE, mapOf(ADDRESS_FIELD to e.reason))

    @ExceptionHandler
    @ResponseStatus(HttpStatus.CONFLICT)
    fun notConfigured(e: SolanaNotConfiguredException): ConsoleErrorResponse =
        errorMapper.toResponse(SOLANA_NOT_CONFIGURED, e.message.orEmpty(), emptyMap())

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadable(e: HttpMessageNotReadableException): ConsoleErrorResponse =
        errorMapper.toResponse(INVALID_REQUEST, UNREADABLE_MESSAGE, emptyMap())

    private companion object {
        const val INVALID_ADDRESS = "INVALID_ADDRESS"
        const val SOLANA_NOT_CONFIGURED = "SOLANA_NOT_CONFIGURED"
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val ADDRESS_FIELD = "address"
        const val INVALID_ADDRESS_MESSAGE = "Not a valid 32-byte base58 Solana address"
        const val UNREADABLE_MESSAGE = "Request body must be a JSON object with a string address"
    }
}
