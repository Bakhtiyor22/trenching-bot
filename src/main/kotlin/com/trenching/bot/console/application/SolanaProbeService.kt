package com.trenching.bot.console.application

import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.config.SolanaRpcProperties
import com.trenching.bot.console.solana.AddressCheckOutcome
import com.trenching.bot.console.solana.AddressProbeCheck
import com.trenching.bot.console.solana.InvalidSolanaAddressException
import com.trenching.bot.console.solana.ProbeCheck
import com.trenching.bot.console.solana.ProbeOutcome
import com.trenching.bot.console.solana.SolanaNotConfiguredException
import com.trenching.bot.console.solana.SolanaProbe
import com.trenching.bot.console.solana.SolanaStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Clock
import com.trenching.bot.config.SimulationClock
import org.springframework.beans.factory.annotation.Qualifier
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * On-demand Solana probe, rate-guarded so repeated clicks cannot burn a paid RPC quota: one lock
 * serialises every probe call, and a caller that waited behind another finds its fresh result cached.
 */
@Service
class SolanaProbeService(
    private val probe: SolanaProbe,
    private val properties: SolanaRpcProperties,
    @param:Qualifier(SimulationClock.NAME) private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(SolanaProbeService::class.java)
    private val inFlight = ReentrantLock()
    @Volatile private var lastCheck: ProbeCheck? = null
    @Volatile private var lastAddressCheck: AddressProbeCheck? = null

    fun status(): SolanaStatus {
        val last = lastCheck
        return SolanaStatus(
            configured = probe.host != null,
            host = probe.host,
            lastCheck = last?.copy(cached = isFresh(last.checkedAt)),
        )
    }

    fun check(): ProbeCheck {
        return inFlight.withLock {
            lastCheck?.takeIf { isFresh(it.checkedAt) }?.let { return it.copy(cached = true) }
            val outcome = timed(OPERATION_CHECK, { it.status }, ::runCheck)
            logCheck(outcome)
            ProbeCheck(outcome, clock.instant(), cached = false).also { lastCheck = it }
        }
    }

    fun checkAddress(rawAddress: String): AddressProbeCheck {
        val address = parseAddress(rawAddress)
        if (probe.host == null) throw SolanaNotConfiguredException()
        return inFlight.withLock {
            lastAddressCheck?.takeIf { it.address == address && isFresh(it.checkedAt) }?.let { return it.copy(cached = true) }
            val outcome = timed(OPERATION_ADDRESS_CHECK, { it.status }) { runAddressCheck(address) }
            logAddressCheck(address, outcome)
            AddressProbeCheck(address, outcome, clock.instant(), cached = false).also { lastAddressCheck = it }
        }
    }

    private fun parseAddress(rawAddress: String): WalletAddress {
        val candidate = rawAddress.trim()
        return try {
            WalletAddress.of(candidate)
        } catch (e: IllegalArgumentException) {
            throw InvalidSolanaAddressException(candidate, e.message ?: "invalid base58 address")
        }
    }

    private fun runCheck(): ProbeOutcome =
        try {
            probe.check()
        } catch (e: RuntimeException) {
            log.error("solana probe failed unexpectedly operation={} host={}", OPERATION_CHECK, probe.host, safeFailure(e))
            UNEXPECTED
        }

    private fun runAddressCheck(address: WalletAddress): AddressCheckOutcome =
        try {
            probe.checkAddress(address)
        } catch (e: RuntimeException) {
            log.error("solana probe failed unexpectedly operation={} host={} address={}",
                OPERATION_ADDRESS_CHECK, probe.host, address.value, safeFailure(e))
            AddressCheckOutcome.Failed(UNEXPECTED)
        }

    // Exception messages, causes and suppressed exceptions can all contain the configured URL.
    private fun safeFailure(error: RuntimeException): RuntimeException =
        RuntimeException("Unexpected ${error.javaClass.name}").also { it.stackTrace = error.stackTrace }

    private fun isFresh(checkedAt: Instant): Boolean = clock.instant().isBefore(checkedAt.plus(properties.cacheTtl))

    private fun <T : Any> timed(operation: String, statusOf: (T) -> Enum<*>, call: () -> T): T {
        val sample = Timer.start(meterRegistry)
        val result = call()
        sample.stop(Timer.builder(METRIC)
            .tag("operation", operation)
            .tag("outcome", statusOf(result).name.lowercase())
            .register(meterRegistry))
        return result
    }

    private fun logCheck(outcome: ProbeOutcome) {
        when (outcome) {
            is ProbeOutcome.Ok -> log.info("solana probe ok host={} cluster={} slot={} latencyMs={} version={}",
                probe.host, outcome.cluster.label, outcome.slot, outcome.latencyMs, outcome.version)
            is ProbeOutcome.Failure -> logFailure(OPERATION_CHECK, outcome)
        }
    }

    private fun logAddressCheck(address: WalletAddress, outcome: AddressCheckOutcome) {
        when (outcome) {
            is AddressCheckOutcome.Checked -> log.info(
                "solana address check host={} address={} signature={} slot={} missingFields={}",
                probe.host, address.value, outcome.signature, outcome.slot, outcome.missingFields)
            is AddressCheckOutcome.NoSuccessfulTransaction -> log.info(
                "solana address check found no successful transaction host={} address={} signaturesExamined={}",
                probe.host, address.value, outcome.signaturesExamined)
            is AddressCheckOutcome.Failed -> logFailure(OPERATION_ADDRESS_CHECK, outcome.failure)
        }
    }

    private fun logFailure(operation: String, failure: ProbeOutcome.Failure) {
        when (failure) {
            ProbeOutcome.NotConfigured -> log.warn("solana probe skipped operation={} reason=not_configured", operation)
            ProbeOutcome.RateLimited, ProbeOutcome.Timeout -> log.warn("solana probe failed operation={} host={} outcome={}",
                operation, probe.host, failure.status)
            is ProbeOutcome.RpcError -> log.warn("solana probe failed operation={} host={} outcome={} code={} message={}",
                operation, probe.host, failure.status, failure.code, failure.message)
            is ProbeOutcome.Unavailable -> log.warn("solana probe failed operation={} host={} outcome={} reason={}",
                operation, probe.host, failure.status, failure.reason)
        }
    }

    private companion object {
        const val METRIC = "trenching.solana.probe"
        const val OPERATION_CHECK = "check"
        const val OPERATION_ADDRESS_CHECK = "address_check"
        val UNEXPECTED = ProbeOutcome.Unavailable("unexpected error")
    }
}
