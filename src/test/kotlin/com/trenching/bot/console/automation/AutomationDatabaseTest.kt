package com.trenching.bot.console.automation

import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.console.automation.application.AutomationDiscoveryService
import com.trenching.bot.console.automation.application.AutomationDiscoveryTransaction
import com.trenching.bot.console.automation.application.AutomationCoverage
import com.trenching.bot.console.automation.application.AutomationDayService
import com.trenching.bot.console.automation.application.AutomationPipelineTransaction
import com.trenching.bot.console.automation.application.AutomationQueryService
import com.trenching.bot.console.automation.application.AutomationRuntime
import com.trenching.bot.console.automation.application.AutomationSessionRows
import com.trenching.bot.console.automation.application.AutomationSessionService
import com.trenching.bot.console.automation.application.AutomationRunCycles
import com.trenching.bot.console.automation.application.AutomationSessionTransaction
import com.trenching.bot.console.automation.application.AutomationSweepTransaction
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import com.trenching.bot.console.config.SolanaRpcProperties
import com.trenching.bot.papertrading.PaperOrderStore
import com.trenching.bot.portfolio.PositionStore
import com.trenching.bot.strategy.TradeIntentStore
import com.trenching.bot.walletactivity.WalletEventStore
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.console.automation.application.AutomationBackfillDiscoveryService
import com.trenching.bot.console.automation.application.AutomationBackfillQueryService
import com.trenching.bot.console.automation.application.AutomationBackfillRecorder
import com.trenching.bot.console.automation.application.AutomationBackfillService
import com.trenching.bot.console.automation.application.AutomationBackfillTransaction
import com.trenching.bot.console.automation.application.AutomationScanWriter
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
    @Autowired protected lateinit var backfills: AutomationBackfillRepository
    @Autowired protected lateinit var sessions: AutomationSessionRepository
    @Autowired protected lateinit var items: AutomationItemRepository
    @Autowired protected lateinit var sweepRuns: AutomationSweepRunRepository
    @Autowired protected lateinit var records: AutomationRecordMapper
    @Autowired protected lateinit var discoveryTransaction: AutomationDiscoveryTransaction
    @Autowired protected lateinit var sessionTransaction: AutomationSessionTransaction
    @Autowired protected lateinit var pipelineTransaction: AutomationPipelineTransaction
    @Autowired protected lateinit var sweepTransaction: AutomationSweepTransaction
    @Autowired protected lateinit var runCycles: AutomationRunCycles
    @Autowired protected lateinit var backfillTransaction: AutomationBackfillTransaction
    @Autowired protected lateinit var backfillRecorder: AutomationBackfillRecorder
    @Autowired protected lateinit var scanWriter: AutomationScanWriter
    @Autowired protected lateinit var coverage: AutomationCoverage

    protected val runtime = AutomationRuntime()

    /** A capture console for the tests that never start capture: a manual close cancels through it. */
    @TempDir lateinit var idleCaptureRoot: Path
    private var idleCapture: CaptureHarness? = null

    @BeforeEach
    fun emptyAutomationTables() {
        listOf(sweepRuns, items, sessions, backfills, watermarks).forEach { it.deleteAllInBatch() }
    }

    @AfterEach
    fun closeIdleCapture() {
        idleCapture?.close()
    }

    protected fun discovery(lookup: FakeWalletActivityLookup, properties: AutomationProperties, clock: Clock): AutomationDiscoveryService =
        AutomationDiscoveryService(lookup, discoveryTransaction, properties, runtime, clock)

    protected fun query(properties: AutomationProperties, clock: Clock, usage: ApiUsageReports = ApiUsageReports { _, _ -> error("unused") }): AutomationQueryService =
        AutomationQueryService(sessions, items, sweepRuns, watermarks, backfills, records, sessionRows(properties), properties, runtime, usage, SolanaRpcProperties(), clock)

    protected fun sessionRows(properties: AutomationProperties): AutomationSessionRows = AutomationSessionRows(items, records, properties)

    protected fun dayService(properties: AutomationProperties, clock: Clock): AutomationDayService =
        AutomationDayService(sessions, items, sweepRuns, coverage, sessionRows(properties), records, properties, clock)

    protected fun sessionService(properties: AutomationProperties, clock: Clock, operations: CaptureOperationService = idleOperations(clock)): AutomationSessionService =
        AutomationSessionService(sessionTransaction, discoveryTransaction, sessions, AutomationCaptureCancellation(operations), query(properties, clock),
            properties, runtime, clock)

    protected fun backfillQuery(properties: AutomationProperties): AutomationBackfillQueryService =
        AutomationBackfillQueryService(backfills, items, records, properties)

    protected fun backfillService(properties: AutomationProperties, clock: Clock, operations: CaptureOperationService = idleOperations(clock)): AutomationBackfillService =
        AutomationBackfillService(backfillTransaction, backfillQuery(properties), AutomationCaptureCancellation(operations), properties, clock)

    protected fun backfillDiscovery(
        lookup: FakeWalletActivityLookup,
        properties: AutomationProperties,
        clock: Clock,
        usage: ApiUsageReports = FakeApiUsageReports(),
    ): AutomationBackfillDiscoveryService = AutomationBackfillDiscoveryService(lookup, backfillTransaction, backfillRecorder, properties, usage, clock)

    private fun idleOperations(clock: Clock): CaptureOperationService =
        (idleCapture ?: CaptureHarness(idleCaptureRoot, clock).also { idleCapture = it }).operations

    @TestConfiguration(proxyBeanMethods = false)
    class Transactions {
        @Bean
        fun automationScanWriter(
            sessions: AutomationSessionRepository,
            items: AutomationItemRepository,
            sweepRuns: AutomationSweepRunRepository,
            mapper: AutomationRecordMapper,
        ) = AutomationScanWriter(sessions, items, sweepRuns, mapper)

        @Bean
        fun automationCoverage(watermarks: AutomationWatermarkRepository, backfills: AutomationBackfillRepository, mapper: AutomationRecordMapper) =
            AutomationCoverage(watermarks, backfills, mapper)

        @Bean
        fun automationDiscoveryTransaction(
            watermarks: AutomationWatermarkRepository,
            sessions: AutomationSessionRepository,
            items: AutomationItemRepository,
            mapper: AutomationRecordMapper,
            writer: AutomationScanWriter,
        ) = AutomationDiscoveryTransaction(watermarks, sessions, items, mapper, writer)

        @Bean
        fun automationBackfillTransaction(
            backfills: AutomationBackfillRepository,
            sessions: AutomationSessionRepository,
            items: AutomationItemRepository,
            watermarks: AutomationWatermarkRepository,
            sessionTransaction: AutomationSessionTransaction,
            coverage: AutomationCoverage,
        ) = AutomationBackfillTransaction(backfills, sessions, items, watermarks, sessionTransaction, coverage)

        @Bean
        fun automationBackfillRecorder(
            backfills: AutomationBackfillRepository,
            sessions: AutomationSessionRepository,
            items: AutomationItemRepository,
            writer: AutomationScanWriter,
        ) = AutomationBackfillRecorder(backfills, sessions, items, writer)

        @Bean
        fun automationSessionTransaction(sessions: AutomationSessionRepository, items: AutomationItemRepository) =
            AutomationSessionTransaction(sessions, items)

        @Bean
        fun automationPipelineTransaction(items: AutomationItemRepository, backfills: AutomationBackfillRepository, mapper: AutomationRecordMapper) =
            AutomationPipelineTransaction(items, backfills, mapper)

        @Bean
        fun automationSweepTransaction(items: AutomationItemRepository, sweepRuns: AutomationSweepRunRepository, mapper: AutomationRecordMapper) =
            AutomationSweepTransaction(items, sweepRuns, mapper)

        @Bean
        fun automationRunCycles(intents: TradeIntentStore, orders: PaperOrderStore, positions: PositionStore, events: WalletEventStore) =
            AutomationRunCycles(intents, orders, positions, events)
    }
}
