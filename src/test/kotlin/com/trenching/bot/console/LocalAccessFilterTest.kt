package com.trenching.bot.console

import com.trenching.bot.console.config.web.LocalAccessFilter
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class LocalAccessFilterTest {
    private val filter = LocalAccessFilter()

    private fun status(uri: String = "/api/captures", host: String = "localhost", peer: String = "127.0.0.1", origin: String? = null, method: String = "GET"): Int {
        val request = MockHttpServletRequest(method, uri).apply {
            serverName = host
            serverPort = 8081
            remoteAddr = peer
            origin?.let { addHeader("Origin", it) }
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response.status
    }

    @Test
    fun `local browsers on the backend or vite origin reach capture routes`() {
        assertEquals(200, status())
        assertEquals(200, status("/api/captures/abc", origin = "http://localhost:5173"))
        assertEquals(200, status(origin = "http://127.0.0.1:8081"))
    }

    @Test
    fun `remote peers foreign origins and tunnel hosts are refused`() {
        assertEquals(403, status(peer = "10.0.0.7"))
        assertEquals(403, status(origin = "https://evil.example"))
        assertEquals(403, status(host = "console.jprq.app"))
    }

    @Test
    fun `other console routes are not affected`() {
        assertEquals(200, status("/api/datasets", host = "console.jprq.app", peer = "10.0.0.7"))
        assertEquals(200, status("/api/datasets/abc", host = "console.jprq.app", peer = "10.0.0.7"))
        assertEquals(200, status("/api/datasets", host = "console.jprq.app", peer = "10.0.0.7", method = "POST"))
    }

    @Test
    fun `wallet activity is refused through the tunnel and served to a local browser`() {
        assertEquals(403, status("/api/wallets/4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9/activity", host = "console.jprq.app"))
        assertEquals(200, status("/api/wallets/4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9/activity", origin = "http://localhost:5173"))
    }

    @Test
    fun `dataset deletion is refused through the tunnel, a remote peer or a foreign origin`() {
        assertEquals(403, status("/api/datasets/abc", host = "console.jprq.app", method = "DELETE"))
        assertEquals(403, status("/api/datasets/abc", peer = "10.0.0.7", method = "DELETE"))
        assertEquals(403, status("/api/datasets/abc", origin = "https://evil.example", method = "DELETE"))
        assertEquals(200, status("/api/datasets/abc", origin = "http://localhost:5173", method = "DELETE"))
    }
}
