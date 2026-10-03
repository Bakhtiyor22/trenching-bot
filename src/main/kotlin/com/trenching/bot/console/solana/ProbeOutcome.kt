package com.trenching.bot.console.solana

sealed interface ProbeOutcome {
    val status: ProbeStatus

    data class Ok(val latencyMs: Long, val version: String, val slot: Long, val cluster: SolanaCluster) : ProbeOutcome {
        override val status get() = ProbeStatus.OK
    }

    sealed interface Failure : ProbeOutcome

    data object NotConfigured : Failure {
        override val status get() = ProbeStatus.NOT_CONFIGURED
    }

    data object RateLimited : Failure {
        override val status get() = ProbeStatus.RATE_LIMITED
    }

    data object Timeout : Failure {
        override val status get() = ProbeStatus.TIMEOUT
    }

    data class RpcError(val code: Long, val message: String) : Failure {
        override val status get() = ProbeStatus.RPC_ERROR
    }

    data class Unavailable(val reason: String) : Failure {
        override val status get() = ProbeStatus.UNAVAILABLE

        companion object {
            val MALFORMED = Unavailable("malformed response")
        }
    }
}
