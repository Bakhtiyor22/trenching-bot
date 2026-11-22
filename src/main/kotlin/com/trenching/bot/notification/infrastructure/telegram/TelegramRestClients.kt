package com.trenching.bot.notification.infrastructure.telegram

import java.net.http.HttpClient
import java.time.Duration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

object TelegramRestClients {
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    val READ_TIMEOUT: Duration = Duration.ofSeconds(10)

    fun create(connectTimeout: Duration, readTimeout: Duration): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(readTimeout) }
        return RestClient.builder().requestFactory(requestFactory).build()
    }
}
