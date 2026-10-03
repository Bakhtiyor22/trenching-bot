package com.trenching.bot.console.solana

import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class SolanaRpcEndpointTest {
    @Test
    fun `redacts once without reprocessing host text containing short credentials`() {
        val endpoint = requireNotNull(SolanaRpcEndpoint.from("https://rpc.example.test/?one=a&two=b&three=c"))

        assertEquals(List(3) { endpoint.host }.joinToString(" "), endpoint.redact("a b c"))
    }

    @Test
    fun `redacts short and percent decoded credentials from provider errors`() {
        val endpoint = requireNotNull(SolanaRpcEndpoint.from(
            "https://usr:p%40ss@rpc.example.test/p%61th?api-key=abc&token=d%2Be"))
        val text = endpoint.redact("invalid abc d+e p@ss usr path p%61th d%2Be")

        listOf("abc", "d+e", "p@ss", "usr", "path", "p%61th", "d%2Be").forEach {
            assertFalse(text.contains(it), "credential remains: $it")
        }
        assertTrue(text.contains(endpoint.host))
    }
}
