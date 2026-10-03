package com.trenching.bot.console.solana

sealed class SolanaProbeException(message: String) : RuntimeException(message)

class InvalidSolanaRpcUrlException :
    SolanaProbeException("trenching.solana.rpc-url must be an absolute http(s) URL with a host")

class SolanaNotConfiguredException :
    SolanaProbeException("Solana RPC is not configured; set SOLANA_RPC_URL")

class InvalidSolanaAddressException(val address: String, val reason: String) :
    SolanaProbeException("Invalid Solana address address=$address reason=$reason")
