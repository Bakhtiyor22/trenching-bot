package com.trenching.bot.console.solana

sealed interface AddressCheckOutcome {
    val status: AddressCheckStatus

    /** [transactionVersion] is the RPC's `version` as text (`legacy`, `0`, `1`), or null when absent. */
    data class Checked(
        val signature: String,
        val slot: Long,
        val transactionVersion: String?,
        val presentFields: List<NormalizerField>,
        val missingFields: List<NormalizerField>,
    ) : AddressCheckOutcome {
        override val status get() = AddressCheckStatus.CHECKED
    }

    data class NoSuccessfulTransaction(val signaturesExamined: Int) : AddressCheckOutcome {
        override val status get() = AddressCheckStatus.NO_SUCCESSFUL_TRANSACTION
    }

    data class Failed(val failure: ProbeOutcome.Failure) : AddressCheckOutcome {
        override val status get() = when (failure) {
            ProbeOutcome.NotConfigured -> AddressCheckStatus.NOT_CONFIGURED
            ProbeOutcome.RateLimited -> AddressCheckStatus.RATE_LIMITED
            ProbeOutcome.Timeout -> AddressCheckStatus.TIMEOUT
            is ProbeOutcome.RpcError -> AddressCheckStatus.RPC_ERROR
            is ProbeOutcome.Unavailable -> AddressCheckStatus.UNAVAILABLE
        }
    }
}
