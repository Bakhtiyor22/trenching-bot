package com.trenching.bot.common

import java.io.UncheckedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ExceptionCausesTest {
    @Test
    fun `the root name is the innermost cause's class, never a wrapper's`() {
        val wrapped = IllegalStateException("POST https://rpc.example/?api-key=secret", UncheckedIOException(SocketTimeoutException("timed out")))

        assertEquals("SocketTimeoutException", ExceptionCauses.rootName(wrapped))
        assertEquals(listOf("IllegalStateException", "UncheckedIOException", "SocketTimeoutException"),
            ExceptionCauses.chain(wrapped).map { it.javaClass.simpleName })
    }

    @Test
    fun `an exception without a cause is its own root`() {
        assertEquals("ConnectException", ExceptionCauses.rootName(ConnectException("refused")))
    }

    @Test
    fun `a cyclic cause chain ends at the first repeated link`() {
        val outer = IllegalStateException("outer")
        val inner = ConnectException("inner")
        outer.initCause(inner)
        inner.initCause(outer)

        assertEquals(listOf<Throwable>(outer, inner), ExceptionCauses.chain(outer))
        assertEquals("ConnectException", ExceptionCauses.rootName(outer))
    }
}
