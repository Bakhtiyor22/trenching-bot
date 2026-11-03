package com.trenching.bot.console.automation.config

import com.trenching.bot.common.DecimalPolicy
import com.trenching.bot.common.WalletAddress
import com.trenching.bot.console.api.dto.CaptureBudget
import com.trenching.bot.console.api.dto.CaptureRecoveryConfig
import com.trenching.bot.console.application.ConsoleAddresses
import com.trenching.bot.console.automation.domain.AutomationRequestCap
import com.trenching.bot.console.automation.domain.CaptureFilter
import com.trenching.bot.console.automation.domain.EvaluationRule
import com.trenching.bot.console.automation.domain.PollWindow
import com.trenching.bot.console.automation.domain.SessionIdleRule
import com.trenching.bot.console.automation.domain.SweepPlan
import com.trenching.bot.console.automation.domain.SweepPlans
import com.trenching.bot.console.automation.domain.UnknownSweepPlanException
import com.trenching.bot.console.capture.CaptureNames
import com.trenching.bot.console.capture.CaptureRequestException
import com.trenching.bot.console.capture.CaptureValidation
import com.trenching.bot.protocol.pumpfun.PumpFunLayout
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

/**
 * Automated discovery, capture and sweep of one wallet's buys (`docs/plans/archive/20261102-decu-capture-sweep-automation.md`).
 * Nothing runs unless [enabled] and the console is enabled under the local profile; [captureEnabled] false is phase 0,
 * discovery only. [operationBudget] and [recovery] are sent with each wallet and market operation automation starts and must
 * pass the capture console's own bounds for both. Startup fails on any value out of bounds, naming the property.
 */
@ConfigurationProperties(AutomationProperties.PREFIX)
class AutomationProperties(
    val enabled: Boolean = false,
    val captureEnabled: Boolean = false,
    val wallet: String? = null,
    val zone: ZoneId = ZoneOffset.ofHours(5),
    val pollFrom: LocalTime = LocalTime.of(18, 0),
    val pollTo: LocalTime = LocalTime.of(5, 0),
    val tickInterval: Duration = Duration.ofMinutes(1),
    val discoveryInterval: Duration = Duration.ofMinutes(10),
    val initialLookback: Duration = Duration.ofHours(12),
    val maxDiscoveryPages: Int = 5,
    val sessionIdle: Duration = Duration.ofMinutes(60),
    val marketDelay: Duration = Duration.ofMinutes(35),
    val captureMinSignalSol: BigDecimal = BigDecimal("1.5"),
    val captureFeeMarginBps: Int = 300,
    val maxStepAttempts: Int = 2,
    val maxRequestsPer24h: Long = 5000,
    val operationBudget: OperationBudget = OperationBudget(),
    val recovery: Recovery = Recovery(),
    val sweepPlan: String = SweepPlans.DECU_SWEEP_1.id,
    val evaluationWindowSessions: Int = 7,
    val minSignals: Int = 10,
    val minNetPnlSol: BigDecimal = BigDecimal.ZERO,
) {
    init {
        require(!enabled || wallet != null) { "$PREFIX.wallet is required when $PREFIX.enabled is true" }
        require(wallet == null || ConsoleAddresses.walletOrNull(wallet) != null) { "$PREFIX.wallet must be a base58 wallet address" }
        require(pollFrom != pollTo) { "$PREFIX.poll-from and $PREFIX.poll-to must differ" }
        requireWithin("tick-interval", tickInterval, MIN_INTERVAL, MAX_INTERVAL)
        requireWithin("discovery-interval", discoveryInterval, MIN_INTERVAL, MAX_INTERVAL)
        requireWithin("initial-lookback", initialLookback, Duration.ZERO, MAX_LOOKBACK)
        require(maxDiscoveryPages in 1..MAX_DISCOVERY_PAGES) { "$PREFIX.max-discovery-pages must be between 1 and $MAX_DISCOVERY_PAGES" }
        requireWithin("session-idle", sessionIdle, MIN_SESSION_IDLE, MAX_SESSION_IDLE)
        require(marketDelay >= MIN_MARKET_DELAY) { "$PREFIX.market-delay must be at least $MIN_MARKET_DELAY, the 4,500-slot market window" }
        require(captureMinSignalSol.signum() > 0 && captureMinSignalSol.stripTrailingZeros().scale() <= PumpFunLayout.SOL_AMOUNT_DECIMALS) {
            "$PREFIX.capture-min-signal-sol must be a positive whole number of lamports"
        }
        require(captureFeeMarginBps in 0..MAX_FEE_MARGIN_BPS) { "$PREFIX.capture-fee-margin-bps must be between 0 and $MAX_FEE_MARGIN_BPS" }
        require(maxStepAttempts in 1..MAX_STEP_ATTEMPTS) { "$PREFIX.max-step-attempts must be between 1 and $MAX_STEP_ATTEMPTS" }
        require(maxRequestsPer24h in MIN_REQUESTS_PER_24H..MAX_REQUESTS_PER_24H) {
            "$PREFIX.max-requests-per-24h must be between $MIN_REQUESTS_PER_24H and $MAX_REQUESTS_PER_24H"
        }
        require(operationBudget.timeout.toSeconds() % SECONDS_PER_MINUTE == 0L) { "$PREFIX.operation-budget.timeout must be whole minutes" }
        requireCaptureBounds("operation-budget") {
            CaptureValidation.budget(CaptureNames.WALLET, captureBudget())
            CaptureValidation.budget(CaptureNames.MARKET, captureBudget())
        }
        requireCaptureBounds("recovery") { CaptureValidation.recovery(captureRecovery(), CaptureNames.WALLET) }
        activePlan().requireCaptureMinimumWithin(captureMinSignalSol)
        require(evaluationWindowSessions in 1..MAX_WINDOW_SESSIONS) { "$PREFIX.evaluation-window-sessions must be between 1 and $MAX_WINDOW_SESSIONS" }
        require(minSignals in 1..MAX_MIN_SIGNALS) { "$PREFIX.min-signals must be between 1 and $MAX_MIN_SIGNALS" }
        require(minNetPnlSol.signum() >= 0 && minNetPnlSol.stripTrailingZeros().scale() <= DecimalPolicy.SCALE) {
            "$PREFIX.min-net-pnl-sol must be at least 0 with at most ${DecimalPolicy.SCALE} decimals"
        }
    }

    /** The tracked wallet; only called while [enabled], which requires it. */
    fun walletAddress(): WalletAddress = WalletAddress.of(requireNotNull(wallet) { "$PREFIX.wallet is not configured" })

    fun activePlan(): SweepPlan = SweepPlans.find(sweepPlan) ?: throw UnknownSweepPlanException(sweepPlan)

    fun pollWindow(): PollWindow = PollWindow(pollFrom, pollTo, zone)

    fun captureFilter(): CaptureFilter = CaptureFilter.of(captureMinSignalSol, captureFeeMarginBps)

    fun idleRule(): SessionIdleRule = SessionIdleRule(sessionIdle)

    fun evaluationRule(): EvaluationRule = EvaluationRule(minSignals, minNetPnlSol)

    /** [maxRequestsPer24h] against an acquisition operation's whole [recovery] allowance, the most its automatic retries may spend. */
    fun requestCap(): AutomationRequestCap = AutomationRequestCap(maxRequestsPer24h, recovery.requests.toLong())

    /** [operationBudget] in the capture console's request shape. */
    fun captureBudget(): CaptureBudget =
        CaptureBudget(operationBudget.requests, operationBudget.bytes.toBytes(), Math.toIntExact(operationBudget.timeout.toMinutes()))

    /** [recovery] in the capture console's request shape. */
    fun captureRecovery(): CaptureRecoveryConfig = CaptureRecoveryConfig(recovery.requests, recovery.bytes.toBytes(), recovery.attempts)

    private fun requireWithin(name: String, value: Duration, min: Duration, max: Duration) =
        require(value >= min && value <= max) { "$PREFIX.$name must be between $min and $max" }

    /** Applies the capture console's own budget and recovery bounds, reporting a refusal as a startup property error. */
    private fun requireCaptureBounds(name: String, check: () -> Unit) {
        try {
            check()
        } catch (e: CaptureRequestException) {
            throw IllegalArgumentException("$PREFIX.$name is outside the capture bounds fields=${e.fields}", e)
        }
    }

    /** One capture operation's allowance: at most [requests] provider requests, [bytes] of responses and [timeout] (whole minutes). */
    data class OperationBudget(
        val requests: Int = 400,
        val bytes: DataSize = DataSize.ofMegabytes(100),
        val timeout: Duration = Duration.ofMinutes(10),
    )

    /** A wallet or market operation's automatic recovery: cumulative [requests] and [bytes] over at most [attempts] attempts. */
    data class Recovery(
        val requests: Int = 1200,
        val bytes: DataSize = DataSize.ofMegabytes(300),
        val attempts: Int = CaptureRecoveryConfig.DEFAULT_MAX_ATTEMPTS,
    )

    companion object {
        const val PREFIX = "trenching.automation"

        private const val SECONDS_PER_MINUTE = 60L
        private val MIN_INTERVAL: Duration = Duration.ofSeconds(10)
        private val MAX_INTERVAL: Duration = Duration.ofHours(1)
        private val MAX_LOOKBACK: Duration = Duration.ofDays(7)
        private const val MAX_DISCOVERY_PAGES = 20
        private val MIN_SESSION_IDLE: Duration = Duration.ofMinutes(10)
        private val MAX_SESSION_IDLE: Duration = Duration.ofHours(6)
        private val MIN_MARKET_DELAY: Duration = Duration.ofMinutes(31)
        private const val MAX_FEE_MARGIN_BPS = 2000
        private const val MAX_STEP_ATTEMPTS = 5
        private const val MIN_REQUESTS_PER_24H = 100L
        private const val MAX_REQUESTS_PER_24H = 1_000_000L
        private const val MAX_WINDOW_SESSIONS = 60
        private const val MAX_MIN_SIGNALS = 1000
    }
}
