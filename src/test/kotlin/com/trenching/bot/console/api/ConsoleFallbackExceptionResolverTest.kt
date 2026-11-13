package com.trenching.bot.console.api

import com.trenching.bot.console.application.RunSetupService
import com.trenching.bot.console.automation.api.AutomationController
import com.trenching.bot.console.automation.api.AutomationExceptionHandler
import com.trenching.bot.console.automation.application.AutomationQueryService
import com.trenching.bot.console.capture.CaptureSessionService
import com.trenching.bot.console.capture.CaptureSetupService
import com.trenching.bot.console.mapper.ConsoleErrorMapperImpl
import com.trenching.bot.replay.domain.DatasetNotFoundException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.catalina.connector.ClientAbortException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import tools.jackson.databind.json.JsonMapper

/** Runs the DispatcherServlet's own resolver chain, so the console advices and the fallback meet in the application's order. */
@ExtendWith(OutputCaptureExtension::class)
class ConsoleFallbackExceptionResolverTest {
    private val json = JsonMapper.builder().build()
    private val errors = ConsoleErrorMapperImpl()
    private val runSetup: RunSetupService = mock()
    private val captureSessions: CaptureSessionService = mock()
    private val captureSetup: CaptureSetupService = mock()
    private val automationQuery: AutomationQueryService = mock()
    private val runSetupController = RunSetupController(runSetup, mock(), mock())
    private val resolver = ConsoleFallbackExceptionResolver(errors, json)

    @Test
    fun `an unexpected failure is a 500 console error whose id names the one ERROR line holding its stack trace`(output: CapturedOutput) {
        Mockito.`when`(runSetup.preset()).thenThrow(DataIntegrityViolationException(STORAGE_FAILURE))

        val response = withMvc { it.response("/api/runs/preset") }

        assertEquals(500, response.status)
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.contentType)
        val body = json.readTree(response.contentAsString)
        assertEquals(setOf("code", "message", "fields", "errorId"), body.propertyNames().toSet())
        assertEquals("INTERNAL_ERROR", body["code"].asString())
        assertEquals(0, body["fields"].size())
        val errorId = body["errorId"].asString()
        assertEquals(errorId, UUID.fromString(errorId).toString())
        assertTrue(body["message"].asString().contains("errorId=$errorId"), body["message"].asString())
        assertFalse(response.contentAsString.contains("check constraint") || response.contentAsString.contains("insert into"), response.contentAsString)
        val logged = output.all.lines().filter { it.contains(errorId) }
        assertEquals(1, logged.size, output.all)
        assertTrue(logged.single().contains("ERROR"), logged.single())
        assertTrue(logged.single().contains("errorId=$errorId method=GET path=/api/runs/preset"), logged.single())
        assertEquals(1, Regex.fromLiteral("DataIntegrityViolationException: $STORAGE_FAILURE").findAll(output.all).count(), output.all)
        assertTrue(output.all.contains("at ${RunSetupController::class.java.name}.preset"), output.all)
    }

    @Test
    fun `every console advice and Spring's own request errors resolve before the fallback`(output: CapturedOutput) {
        Mockito.`when`(runSetup.recommendations("gone")).thenThrow(DatasetNotFoundException("gone"))
        Mockito.`when`(captureSessions.checkName("bad")).thenThrow(IllegalArgumentException("bad name"))

        withMvc { mvc ->
            assertError(404, "DATASET_NOT_FOUND", mvc.response("/api/runs/recommendations?datasetId=gone"))
            assertError(400, "INVALID_REQUEST", mvc.response("/api/captures/names/check?name=bad"))
            assertError(400, "INVALID_REQUEST", mvc.response("/api/automation/sessions?page=first"))
            assertEquals(400, mvc.response("/api/runs/recommendations").status)
        }

        assertFalse(output.all.contains("errorId="), output.all)
    }

    @Test
    fun `the capture and automation endpoints answer an unexpected failure with the same body`() {
        Mockito.`when`(captureSetup.config()).thenThrow(IllegalStateException("capture root is gone"))
        Mockito.`when`(automationQuery.status()).thenThrow(IllegalStateException("automation state is broken"))

        withMvc { mvc ->
            assertError(500, "INTERNAL_ERROR", mvc.response("/api/captures/config"))
            assertError(500, "INTERNAL_ERROR", mvc.response("/api/automation/status"))
        }
    }

    @Test
    fun `a client that went away and a failure outside the console are left to Spring`(output: CapturedOutput) {
        val consoleHandler = HandlerMethod(runSetupController, RunSetupController::class.java.getMethod("preset"))
        val otherHandler = HandlerMethod(Any(), Any::class.java.getMethod("toString"))

        assertNull(resolve(consoleHandler, ClientAbortException()))
        assertNull(resolve(otherHandler, IllegalStateException("not a console failure")))
        assertNull(resolve(null, IllegalStateException("no handler")))
        assertFalse(output.all.contains("errorId="), output.all)
    }

    private fun <T> withMvc(test: (MockMvc) -> T): T =
        AnnotationConfigWebApplicationContext().use { context ->
            context.servletContext = MockServletContext()
            context.register(Web::class.java)
            context.addBeanFactoryPostProcessor { beans ->
                listOf(
                    runSetupController,
                    CaptureController(captureSessions, captureSetup, mock(), mock(), mock(), mock(), mock(), mock()),
                    AutomationController(automationQuery, mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock()),
                    ConsoleExceptionHandler(errors),
                    CaptureExceptionHandler(errors, mock()),
                    AutomationExceptionHandler(errors),
                    resolver,
                ).forEach { beans.registerSingleton(it.javaClass.simpleName, it) }
            }
            context.refresh()
            test(MockMvcBuilders.webAppContextSetup(context).build())
        }

    private fun MockMvc.response(path: String): MockHttpServletResponse = perform(get(path)).andReturn().response

    private fun assertError(status: Int, code: String, response: MockHttpServletResponse) {
        assertEquals(status, response.status, response.contentAsString)
        assertEquals(code, json.readTree(response.contentAsString)["code"].asString(), response.contentAsString)
    }

    private fun resolve(handler: Any?, failure: Exception) =
        resolver.resolveException(MockHttpServletRequest(), MockHttpServletResponse(), handler, failure)

    private inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    class Web

    private companion object {
        const val STORAGE_FAILURE = "ERROR: new row for relation \"positions\" violates check constraint \"positions_state_check\" " +
            "[insert into positions (id, state) values (?, ?)]"
    }
}
