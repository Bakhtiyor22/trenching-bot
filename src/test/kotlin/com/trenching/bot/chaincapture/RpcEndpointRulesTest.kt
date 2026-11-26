package com.trenching.bot.chaincapture

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RpcEndpointRulesTest {
    @ParameterizedTest
    @ValueSource(strings = ["https://rpc.example.test/?api-key=abc", "http://localhost:8899", "http://127.0.0.1:8899/", "http://[::1]:8899", "HTTPS://rpc.example.test"])
    fun `an https address or a local http address is accepted`(value: String) {
        assertNull(RpcEndpointRules.violation(value))
    }

    @ParameterizedTest
    @ValueSource(strings = ["not a url", "ftp://rpc.example.test", "https:///path", "https://user:pw@rpc.example.test", "https://rpc.example.test/#frag", ""])
    fun `an address that is not usable is invalid`(value: String) {
        assertEquals(RpcEndpointRules.INVALID, RpcEndpointRules.violation(value))
    }

    @Test
    fun `plain http to a remote host needs tls`() {
        assertEquals(RpcEndpointRules.NOT_TLS, RpcEndpointRules.violation("http://rpc.example.test/key"))
    }

    @Test
    fun `parsing a refused address fails with its code and never quotes it`() {
        val failure = assertFailsWith<CaptureValidationException> { RpcEndpointRules.uri("http://rpc.example.test/SECRET-KEY") }

        assertEquals(RpcEndpointRules.NOT_TLS, failure.category)
        assertFalse(failure.message.orEmpty().contains("SECRET"))
    }
}
