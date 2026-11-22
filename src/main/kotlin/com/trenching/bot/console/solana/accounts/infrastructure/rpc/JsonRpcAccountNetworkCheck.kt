package com.trenching.bot.console.solana.accounts.infrastructure.rpc

import com.trenching.bot.apiusage.ApiUsageRecorder
import com.trenching.bot.console.solana.RpcReply
import com.trenching.bot.console.solana.SolanaCluster
import com.trenching.bot.console.solana.SolanaRpc
import com.trenching.bot.console.solana.SolanaRpcClient
import com.trenching.bot.console.solana.SolanaRpcEndpoint
import com.trenching.bot.console.solana.accounts.application.RpcAccountNetworkCheck
import com.trenching.bot.console.solana.accounts.domain.NetworkCheck
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

/** Sends one `getGenesisHash` request to an account's endpoint, recorded as a mainnet check. An answer that is not a hash, or none, says nothing about the network. */
class JsonRpcAccountNetworkCheck(
    private val accounts: RpcAccountConfigs,
    private val restClient: RestClient,
    private val mapper: ObjectMapper,
    private val recorder: ApiUsageRecorder,
) : RpcAccountNetworkCheck {
    override fun check(account: RpcAccountName): NetworkCheck {
        val config = accounts[account] ?: return NetworkCheck.Unknown
        val endpoint = SolanaRpcEndpoint.from(config.endpoint) ?: return NetworkCheck.Unknown
        val reply = SolanaRpcClient(restClient, endpoint, mapper, recorder).call(SolanaRpc.GET_GENESIS_HASH)
        val hash = (reply as? RpcReply.Success)?.result?.takeIf { it.isString }?.asString() ?: return NetworkCheck.Unknown
        return if (SolanaCluster.fromGenesisHash(hash) == SolanaCluster.MAINNET_BETA) NetworkCheck.Mainnet else NetworkCheck.OtherNetwork
    }
}
