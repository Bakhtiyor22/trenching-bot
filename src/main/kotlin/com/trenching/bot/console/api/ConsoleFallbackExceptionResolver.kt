package com.trenching.bot.console.api

import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import com.trenching.bot.console.mapper.ConsoleErrorMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.method.HandlerTypePredicate
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.util.DisconnectedClientHelper
import tools.jackson.databind.ObjectMapper

/**
 * The console error body, code `INTERNAL_ERROR`, for a console failure that nothing else maps, with an `errorId` that finds
 * its one `ERROR` log line. It is a resolver ordered after Spring MVC's own rather than an `@ExceptionHandler` for `Exception`:
 * that would also take Spring's standard 400s, and it would win over any specific advice registered after it at the same,
 * default, order.
 */
@Component
@ConditionalOnConsoleEnabled
class ConsoleFallbackExceptionResolver(
    private val consoleErrorMapper: ConsoleErrorMapper,
    private val objectMapper: ObjectMapper,
) : HandlerExceptionResolver, Ordered {
    private val log = LoggerFactory.getLogger(ConsoleFallbackExceptionResolver::class.java)

    override fun resolveException(request: HttpServletRequest, response: HttpServletResponse, handler: Any?, ex: Exception): ModelAndView? {
        if (!isConsoleHandler(handler) || DisconnectedClientHelper.isClientDisconnectedException(ex)) return null
        val errorId = UUID.randomUUID().toString()
        log.error("Unexpected error in the console API; the caller got an error id to look it up. errorId={} method={} path={}",
            errorId, request.method, request.requestURI, ex)
        response.status = HttpStatus.INTERNAL_SERVER_ERROR.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.outputStream.write(objectMapper.writeValueAsBytes(
            consoleErrorMapper.toInternalError(INTERNAL_ERROR, message(errorId), emptyMap(), errorId)))
        return ModelAndView()
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    private fun isConsoleHandler(handler: Any?): Boolean = handler is HandlerMethod && CONSOLE_HANDLERS.test(handler.beanType)

    private fun message(errorId: String): String = "Unexpected backend error. Look up errorId=$errorId in the backend log."

    private companion object {
        const val INTERNAL_ERROR = "INTERNAL_ERROR"
        val CONSOLE_HANDLERS: HandlerTypePredicate = HandlerTypePredicate.forBasePackage("com.trenching.bot.console")
    }
}
