package com.trenching.bot.console.solana

import com.trenching.bot.common.WalletAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FakeSolanaProbe(
    override val host: String?,
    private val outcome: ProbeOutcome = ProbeOutcome.RateLimited,
    private val addressOutcome: AddressCheckOutcome = AddressCheckOutcome.NoSuccessfulTransaction(5),
) : SolanaProbe {
    private val checks = AtomicInteger()
    private val addressChecks = AtomicInteger()
    private val release = CountDownLatch(1)
    @Volatile private var blocking = false
    @Volatile var failure: RuntimeException? = null

    val checkCount: Int get() = checks.get()
    val addressCheckCount: Int get() = addressChecks.get()

    fun blockUntilReleased() {
        blocking = true
    }

    fun release() {
        release.countDown()
    }

    override fun check(): ProbeOutcome {
        checks.incrementAndGet()
        if (blocking) release.await(1, TimeUnit.MINUTES)
        failure?.let { throw it }
        return outcome
    }

    override fun checkAddress(address: WalletAddress): AddressCheckOutcome {
        addressChecks.incrementAndGet()
        failure?.let { throw it }
        return addressOutcome
    }
}
