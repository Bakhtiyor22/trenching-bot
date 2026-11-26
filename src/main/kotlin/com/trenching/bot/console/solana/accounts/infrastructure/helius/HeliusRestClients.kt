package com.trenching.bot.console.solana.accounts.infrastructure.helius

import com.trenching.bot.console.solana.SolanaRestClients
import java.time.Duration
import org.springframework.web.client.RestClient

/** The client for the Admin API: 5 seconds to connect and 10 to read, with no retry of its own. */
object HeliusRestClients {
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    val READ_TIMEOUT: Duration = Duration.ofSeconds(10)

    fun create(): RestClient = SolanaRestClients.create(CONNECT_TIMEOUT, READ_TIMEOUT)
}
