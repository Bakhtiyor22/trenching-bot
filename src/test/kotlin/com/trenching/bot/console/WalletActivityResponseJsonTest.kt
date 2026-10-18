package com.trenching.bot.console

import com.trenching.bot.console.api.dto.ConsoleErrorResponse
import com.trenching.bot.console.api.dto.WalletActionCaptureResponse
import com.trenching.bot.console.api.dto.WalletActionResponse
import com.trenching.bot.console.api.dto.WalletActivityResponse
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper

/** Serializes with the mapper the application gets from Spring Boot, so the field names are the ones the frontend reads. */
class WalletActivityResponseJsonTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))

    @Test
    fun `the page keeps its contract names, nulls and string amounts`() {
        runner.run { context ->
            val json = context.getBean(JsonMapper::class.java)
            val action = WalletActionResponse("sig", 1, null, 7, null, "BUY", "mint", "18446744073709551615", "1238520682656", 6, "PUMPFUN_CURVE",
                earlierActionInPage = true, overSignalThreshold = false, signalBasis = "CURVE_PAYMENT_EXCLUDING_FEES",
                capturedIn = listOf(WalletActionCaptureResponse("c1", "Decu")))
            val page = WalletActivityResponse("wallet", "pumpfun-user-volume-accumulator", listOf(action), 1, 6, null, 7, Instant.parse("2026-10-17T10:00:00Z"))

            val node = json.readTree(json.writeValueAsString(page))

            assertTrue(node["nextBefore"].isNull)
            assertEquals("2026-10-17T10:00:00Z", node["cachedAt"].asString())
            val first = node["actions"][0]
            assertTrue(first["innerIndex"].isNull && first["blockTime"].isNull)
            assertEquals("18446744073709551615", first["solLamports"].asString())
            assertTrue(first["earlierActionInPage"].asBoolean())
            assertEquals(false, first["overSignalThreshold"].asBoolean())
            assertEquals("""[{"captureId":"c1","displayName":"Decu"}]""", first["capturedIn"].toString())
        }
    }

    @Test
    fun `retryAfterSeconds is a top-level number only when present`() {
        runner.run { context ->
            val json = context.getBean(JsonMapper::class.java)

            assertEquals("""{"code":"PROVIDER_RATE_LIMITED","message":"m","fields":{},"retryAfterSeconds":12}""",
                json.writeValueAsString(ConsoleErrorResponse("PROVIDER_RATE_LIMITED", "m", emptyMap(), 12)))
            assertEquals("""{"code":"PROVIDER_UNAVAILABLE","message":"m","fields":{}}""",
                json.writeValueAsString(ConsoleErrorResponse("PROVIDER_UNAVAILABLE", "m", emptyMap(), null)))
        }
    }
}
