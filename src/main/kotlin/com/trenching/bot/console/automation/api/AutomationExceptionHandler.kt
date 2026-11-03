package com.trenching.bot.console.automation.api

import com.trenching.bot.console.api.dto.ConsoleErrorResponse
import com.trenching.bot.console.automation.config.ConditionalOnAutomationConsole
import com.trenching.bot.console.automation.domain.AutomationItemNotFoundException
import com.trenching.bot.console.automation.domain.AutomationSessionNotFoundException
import com.trenching.bot.console.automation.domain.InvalidAutomationRequestException
import com.trenching.bot.console.mapper.ConsoleErrorMapper
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** The console error body for the automation endpoints. */
@RestControllerAdvice(assignableTypes = [AutomationController::class])
@ConditionalOnAutomationConsole
class AutomationExceptionHandler(private val mapper: ConsoleErrorMapper) {
    @ExceptionHandler
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun sessionNotFound(e: AutomationSessionNotFoundException): ConsoleErrorResponse = mapper.toResponse(SESSION_NOT_FOUND, e.message.orEmpty(), emptyMap())

    @ExceptionHandler
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun itemNotFound(e: AutomationItemNotFoundException): ConsoleErrorResponse = mapper.toResponse(ITEM_NOT_FOUND, e.message.orEmpty(), emptyMap())

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(e: InvalidAutomationRequestException): ConsoleErrorResponse =
        mapper.toResponse(INVALID_REQUEST, e.message.orEmpty(), mapOf(e.field to e.reason))

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun typeMismatch(e: MethodArgumentTypeMismatchException): ConsoleErrorResponse =
        mapper.toResponse(INVALID_REQUEST, INVALID_PARAMETER_MESSAGE, mapOf(e.name to INVALID_VALUE))

    @ExceptionHandler
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadable(e: HttpMessageNotReadableException): ConsoleErrorResponse = mapper.toResponse(INVALID_REQUEST, UNREADABLE_MESSAGE, emptyMap())

    @ExceptionHandler
    @ResponseStatus(HttpStatus.CONFLICT)
    fun concurrentChange(e: OptimisticLockingFailureException): ConsoleErrorResponse = mapper.toResponse(CONFLICT, CONFLICT_MESSAGE, emptyMap())

    private companion object {
        const val SESSION_NOT_FOUND = "AUTOMATION_SESSION_NOT_FOUND"
        const val ITEM_NOT_FOUND = "AUTOMATION_ITEM_NOT_FOUND"
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val CONFLICT = "AUTOMATION_CONFLICT"
        const val INVALID_VALUE = "has an invalid value"
        const val INVALID_PARAMETER_MESSAGE = "Request parameter has an invalid value"
        const val UNREADABLE_MESSAGE = "Request body must be valid JSON of the expected shape"
        const val CONFLICT_MESSAGE = "The session changed while the request ran; retry it"
    }
}
