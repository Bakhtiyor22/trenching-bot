package com.trenching.bot.console.automation

import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.console.automation.application.AutomationDiscoveryService
import com.trenching.bot.console.automation.application.AutomationDiscoveryTransaction
import com.trenching.bot.console.automation.application.AutomationEvaluationService
import com.trenching.bot.console.automation.application.AutomationEvaluationTransaction
import com.trenching.bot.console.automation.application.AutomationPipelineTransaction
import com.trenching.bot.console.automation.application.AutomationQueryService
import com.trenching.bot.console.automation.application.AutomationRuntime
import com.trenching.bot.console.automation.application.AutomationSessionService
import com.trenching.bot.console.automation.application.AutomationRunCycles
import com.trenching.bot.console.automation.application.AutomationSessionTransaction
import com.trenching.bot.console.automation.application.AutomationSweepTransaction
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationEvaluationRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import com.trenching.bot.console.automation.mapper.EvaluationWindowMapper
import com.trenching.bot.console.config.SolanaRpcProperties
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.console.automation.application.AutomationCaptureCancellation
import com.trenching.bot.console.capture.CaptureOperationService
import java.nio.file.Path
import java.time.Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * The automation services against PostgreSQL. Automation loads only under the local profile, so the transactional beans
 * are declared here (and proxied as in the application); each test builds the plain services around them with its own
 * clock, properties and fake wallet activity. Every test starts from empty automation tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["spring.application.name=automation"])
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class, AutomationDatabaseTest.Transactions::class)
abstract class AutomationDatabaseTest {
    @Autowired protected lateinit var watermarks: AutomationWatermarkRepository
    @Autowired protected lateinit var sessions: AutomationSessionRepository
    @Autowired protected lateinit var items: AutomationItemRepository
    @Autowired protected lateinit var sweepRuns: AutomationSweepRunRepository
    @Autowired protected lateinit var evaluations: AutomationEvaluationRepository
    @Autowired protected lateinit var records: AutomationRecordMapper
    @Autowired protected lateinit var discoveryTransaction: AutomationDiscoveryTransaction
    @Autowired protected lateinit var sessionTransaction: AutomationSessionTransaction
    @Autowired protected lateinit var pipelineTransaction: AutomationPipelineTransaction
    @Autowired protected lateinit var sweepTransaction: AutomationSweepTransaction
    @Autowired protected lateinit var evaluationTransaction: AutomationEvaluationTransaction
    @Autowired protected lateinit var runCycles: AutomationRunCycles

    protected val runtime = AutomationRuntime()

    /** A capture console for the tests that never start capture: a manual close cancels through it. */
    @TempDir lateinit var idleCaptureRoot: Path
    private var idleCapture: CaptureHarness? = null

    @BeforeEach
    fun emptyAutomationTables() {
        listOf(evaluations, sweepRuns, items, sessions, watermarks).forEach { it.deleteAllInBatch() }
    }

    @AfterEach
    fun closeIdleCapture() {
        idleCapture?.close()
    }

    protected fun discovery(lookup: FakeWalletActivityLookup, properties: AutomationProperties, clock: Clock): AutomationDiscoveryService =
        AutomationDiscoveryService(lookup, discoveryTransaction, properties, runtime, clock)

    protected fun query(properties: AutomationProperties, clock: Clock, usage: ApiUsageReports = ApiUsageReports { _, _ -> error("unused") }): AutomationQueryService =
        AutomationQueryService(sessions, items, sweepRuns, evaluations, watermarks, records, properties, runtime, usage, SolanaRpcProperties(), clock)

    protected fun evaluationService(properties: AutomationProperties, clock: Clock): AutomationEvaluationService =
        AutomationEvaluationService(evaluationTransaction, properties, clock)

    protected fun sessionService(properties: AutomationProperties, clock: Clock, operations: CaptureOperationService = idleOperations(clock)): AutomationSessionService =
        AutomationSessionService(sessionTransaction, discoveryTransaction, sessions, AutomationCaptureCancellation(operations), query(properties, clock),
            evaluationService(properties, clock), properties, runtime, clock)

    private fun idleOperations(clock: Clock): CaptureOperationService =
        (idleCapture ?: CaptureHarness(idleCaptureRoot, clock).also { idleCapture = it }).operations

    @TestConfiguration(proxyBeanMethods = false)
    class Transactions {
        @Bean
        fun automationDiscoveryTransaction(
            watermarks: AutomationWatermarkRepository,
            sessions: AutomationSessionRepository,
            items: AutomationItemRepository,
            sweepRuns: AutomationSweepRunRepository,
            mapper: AutomationRecordMapper,
        ) = AutomationDiscoveryTransaction(watermarks, sessions, items, sweepRuns, mapper)

        @Bean
        fun automationSessionTransaction(sessions: AutomationSessionRepository, items: AutomationItemRepository) =
            AutomationSessionTransaction(sessions, items)

        @Bean
        fun automationPipelineTransaction(items: AutomationItemRepository, mapper: AutomationRecordMapper) =
            AutomationPipelineTransaction(items, mapper)

        @Bean
        fun automationSweepTransaction(items: AutomationItemRepository, sweepRuns: AutomationSweepRunRepository, mapper: AutomationRecordMapper) =
            AutomationSweepTransaction(items, sweepRuns, mapper)

        @Bean
        fun automationEvaluationTransaction(
            sessions: AutomationSessionRepository,
            items: AutomationItemRepository,
            sweepRuns: AutomationSweepRunRepository,
            evaluations: AutomationEvaluationRepository,
            mapper: AutomationRecordMapper,
            window: EvaluationWindowMapper,
        ) = AutomationEvaluationTransaction(sessions, items, sweepRuns, evaluations, mapper, window)

        @Bean
        fun automationRunCycles(intents: TradeIntentStore, orders: PaperOrderStore, positions: PositionStore, events: WalletEventStore) =
            AutomationRunCycles(intents, orders, positions, events)
    }
}
