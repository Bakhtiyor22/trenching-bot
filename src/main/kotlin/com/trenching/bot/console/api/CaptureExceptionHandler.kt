package com.trenching.bot.console.api

import com.trenching.bot.console.api.dto.ConsoleErrorResponse
import com.trenching.bot.console.capture.CaptureRequestException
import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.ConsoleErrorMapper
import com.trenching.bot.replay.domain.DatasetInvalidException
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice(assignableTypes=[CaptureController::class])
@Profile("local & !prod")
@ConditionalOnConsoleEnabled
class CaptureExceptionHandler(private val mapper: ConsoleErrorMapper) {
    @ExceptionHandler fun capture(exception: CaptureRequestException): ResponseEntity<ConsoleErrorResponse> = ResponseEntity.status(exception.status).body(mapper.toResponse(exception.code,exception.message,exception.fields))
    @ExceptionHandler(HttpMessageNotReadableException::class,MethodArgumentTypeMismatchException::class,IllegalArgumentException::class,DatasetInvalidException::class)
    fun invalid(): ResponseEntity<ConsoleErrorResponse> = ResponseEntity.badRequest().body(mapper.toResponse("INVALID_REQUEST","Invalid capture request or artifact",emptyMap()))
}
