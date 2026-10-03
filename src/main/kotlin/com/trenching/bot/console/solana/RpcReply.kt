package com.trenching.bot.console.solana

import tools.jackson.databind.JsonNode

sealed interface RpcReply {
    /** [result] is null when the RPC answered with a null or absent result. */
    data class Success(val result: JsonNode?) : RpcReply

    data class Failed(val failure: ProbeOutcome.Failure) : RpcReply
}
