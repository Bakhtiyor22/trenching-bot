package com.trenching.bot.console.solana.accounts.infrastructure.rpc

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.console.solana.RpcReply
import com.trenching.bot.console.solana.SolanaRpc
import com.trenching.bot.console.solana.SolanaRpcClient
import com.trenching.bot.console.solana.SolanaRpcEndpoint
import com.trenching.bot.console.solana.accounts.application.RpcAccountHealth
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

/** Sends one `getHealth` request to an account's endpoint, counted as a connection check. A provider that refuses or stays silent has not answered. */
class JsonRpcAccountHealth(
    private val accounts: RpcAccountConfigs,
    private val restClient: RestClient,
    private val mapper: ObjectMapper,
    private val recorder: ApiUsageRecorder,
) : RpcAccountHealth {
    override fun answers(account: RpcAccountName): Boolean {
        val config = accounts[account] ?: return false
        val endpoint = SolanaRpcEndpoint.from(config.endpoint) ?: return false
        val reply = SolanaRpcClient(restClient, endpoint, mapper, recorder).call(SolanaRpc.GET_HEALTH)
        return reply is RpcReply.Success && reply.result?.asString() == SolanaRpc.HEALTHY
    }
}
