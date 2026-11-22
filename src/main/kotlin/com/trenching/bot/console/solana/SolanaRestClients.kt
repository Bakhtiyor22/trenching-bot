package com.trenching.bot.console.solana

import java.net.http.HttpClient
import java.time.Duration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

/** The one way the console builds a `RestClient` for a provider: HTTP/1.1 with explicit connect and read timeouts. */
object SolanaRestClients {
    fun create(connectTimeout: Duration, readTimeout: Duration): RestClient {
        val httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(readTimeout) }
        return RestClient.builder().requestFactory(requestFactory).build()
    }
}
