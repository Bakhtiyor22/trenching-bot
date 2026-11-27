package com.trenching.bot.console.automation

import com.trenching.bot.apiusage.ApiUsageReports
import com.trenching.bot.console.application.ConsoleConfigValidator
import com.trenching.bot.console.automation.application.AutomationDiscoveryService
import com.trenching.bot.console.automation.application.AutomationHolds
import com.trenching.bot.console.automation.application.AutomationDiscoveryTransaction
import com.trenching.bot.console.automation.application.AutomationBuyNotifier
import com.trenching.bot.console.automation.application.AutomationCoverage
import com.trenching.bot.console.automation.application.AutomationDayService
import com.trenching.bot.console.automation.application.AutomationHistoryStartTransaction
import com.trenching.bot.console.automation.application.AutomationItemEventWriter
import com.trenching.bot.console.automation.application.AutomationItemLog
import com.trenching.bot.console.automation.application.AutomationItemViews
import com.trenching.bot.console.automation.application.AutomationPipelineTransaction
import com.trenching.bot.console.automation.application.AutomationRetryService
import com.trenching.bot.console.automation.application.AutomationRetryTransaction
import com.trenching.bot.console.automation.application.AutomationQueryService
import com.trenching.bot.console.automation.application.AutomationRuntime
import com.trenching.bot.console.automation.application.AutomationSessionRows
import com.trenching.bot.console.automation.application.AutomationSessionService
import com.trenching.bot.console.automation.application.AutomationRunCycles
import com.trenching.bot.console.automation.application.AutomationSessionTransaction
import com.trenching.bot.console.automation.application.AutomationSummaryTransaction
import com.trenching.bot.console.automation.application.AutomationVariationSeedTransaction
import com.trenching.bot.console.automation.application.AutomationVariationService
import com.trenching.bot.console.automation.application.AutomationVariationTransaction
import com.trenching.bot.console.automation.application.AutomationVariationViews
import com.trenching.bot.console.automation.application.AutomationSweepTransaction
import com.trenching.bot.console.automation.config.AutomationProperties
import com.trenching.bot.console.automation.domain.AutomationLimits
import com.trenching.bot.console.automation.domain.VariationState
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationBackfillRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemEventRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationItemRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSessionRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationSweepRunRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationVariationRepository
import com.trenching.bot.console.automation.infrastructure.persistence.AutomationWatermarkRepository
import com.trenching.bot.console.automation.mapper.AutomationRecordMapper
import com.trenching.bot.console.automation.mapper.AutomationVariationMapper
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
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The automation services against PostgreSQL. Automation loads only under the local profile, so the transactional beans
 * are declared here (and proxied as in the application); each test builds the plain services around them with its own
 * clock, properties and fake wallet activity. Every test starts from empty automation tables and the plan's seeded variations,
 * V00 to V09 as the application's first start stores them; a test that edits writes the archived row and its replacement itself.
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
    @Autowired protected lateinit var itemEvents: AutomationItemEventRepository
    @Autowired protected lateinit var variationRows: AutomationVariationRepository
    @Autowired protected lateinit var variationMapper: AutomationVariationMapper
    @Autowired protected lateinit var variationSeed: AutomationVariationSeedTransaction
    @Autowired protected lateinit var variationTransaction: AutomationVariationTransaction
    @Autowired protected lateinit var records: AutomationRecordMapper
    @Autowired protected lateinit var discoveryTransaction: AutomationDiscoveryTransaction
    @Autowired protected lateinit var sessionTransaction: AutomationSessionTransaction
    @Autowired protected lateinit var pipelineTransaction: AutomationPipelineTransaction
    @Autowired protected lateinit var sweepTransaction: AutomationSweepTransaction
    @Autowired protected lateinit var retryTransaction: AutomationRetryTransaction
    @Autowired protected lateinit var runCycles: AutomationRunCycles
    @Autowired protected lateinit var backfillTransaction: AutomationBackfillTransaction
    @Autowired protected lateinit var backfillRecorder: AutomationBackfillRecorder
    @Autowired protected lateinit var scanWriter: AutomationScanWriter
    @Autowired protected lateinit var coverage: AutomationCoverage
    @Autowired protected lateinit var itemEventWriter: AutomationItemEventWriter
    @Autowired protected lateinit var itemLog: AutomationItemLog
    @Autowired protected lateinit var historyStartTransaction: AutomationHistoryStartTransaction
    @Autowired protected lateinit var summaryTransaction: AutomationSummaryTransaction
    @Autowired protected lateinit var notifications: SwitchableNotifications
    @Autowired protected lateinit var buyNotifier: AutomationBuyNotifier

    @Autowired private lateinit var lockProbe: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    protected val runtime = AutomationRuntime()

    /** A capture console for the tests that never start capture: a manual close cancels through it. */
    @TempDir lateinit var idleCaptureRoot: Path
    private var idleCapture: CaptureHarness? = null

    @BeforeEach
    fun emptyAutomationTables() {
        listOf(itemEvents, sweepRuns, items, sessions, backfills, watermarks, variationRows).forEach { it.deleteAllInBatch() }
        variations(AutomationFixtures.properties(), Clock.systemUTC()).seed()
        notifications.reset()
    }

    @AfterEach
    fun closeIdleCapture() {
        idleCapture?.close()
    }

    /**
     * Waits until a session of this database waits for a row lock, or until [finished] holds, or a few seconds pass: the moment a
     * second writer has reached the one a test holds open. Whichever ends the wait, the test asserts what happened.
     */
    protected fun awaitLockWaiterOr(finished: () -> Boolean) {
        val deadline = System.nanoTime() + LOCK_WAIT_LIMIT.toNanos()
        while (System.nanoTime() < deadline && !finished()) {
            val waiting = lockProbe.queryForObject("select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'", Long::class.java)
            if (waiting != null && waiting > 0) return
            Thread.sleep(LOCK_POLL_MILLIS)
        }
    }

    /**
     * Runs [tasks] on threads of their own while the test holds the configured plan's active variations locked, and lets go once they are all
     * under way and a session waits for the lock: the operations really overlap, so only the lock puts them one after the other.
     */
    protected fun <T> concurrentlyBehindLock(vararg tasks: () -> T): List<Result<T>> {
        val started = CountDownLatch(tasks.size)
        val pool = Executors.newFixedThreadPool(tasks.size)
        try {
            val futures = checkNotNull(TransactionTemplate(transactionManager).execute {
                variationRows.findAllForUpdateByPlanIdAndStateOrderBySeqAsc(
                    AutomationFixtures.properties().sweepPlan, VariationState.ACTIVE, Limit.of(AutomationLimits.MAX_VARIATIONS_PER_PLAN),
                )
                val running = tasks.map { task -> pool.submit(Callable { started.countDown(); runCatching(task) }) }
                assertTrue(started.await(30, TimeUnit.SECONDS), "every task started")
                awaitLockWaiterOr { false }
                running
            })
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    protected fun discovery(lookup: FakeWalletActivityLookup, properties: AutomationProperties, clock: Clock): AutomationDiscoveryService =
        AutomationDiscoveryService(lookup, discoveryTransaction, properties, runtime, itemLog, clock)

    protected fun query(properties: AutomationProperties, clock: Clock, usage: ApiUsageReports = ApiUsageReports { _, _ -> error("unused") }): AutomationQueryService =
        AutomationQueryService(sessions, items, sweepRuns, itemEvents, watermarks, backfills, records, sessionRows(properties), itemViews(properties), properties, runtime, usage, SolanaRpcProperties(), clock)

    protected fun itemViews(properties: AutomationProperties): AutomationItemViews = AutomationItemViews(backfills, records, properties)

    protected fun retryService(properties: AutomationProperties, clock: Clock): AutomationRetryService =
        AutomationRetryService(retryTransaction, sessions, items, query(properties, clock), properties, clock)

    protected fun sessionRows(properties: AutomationProperties): AutomationSessionRows = AutomationSessionRows(items, records, properties)

    protected fun variations(properties: AutomationProperties, clock: Clock, seeding: AutomationVariationSeedTransaction = variationSeed): AutomationVariationService =
        AutomationVariationService(variationRows, seeding, variationTransaction, AutomationVariationViews(variationRows, variationMapper), variationMapper, ConsoleConfigValidator(), properties, clock)

    protected fun dayService(properties: AutomationProperties, clock: Clock): AutomationDayService =
        AutomationDayService(sessions, items, sweepRuns, variations(properties, clock), coverage, sessionRows(properties), itemViews(properties), records, properties, clock)

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
        holds: AutomationHolds = AutomationHolds(clock),
    ): AutomationBackfillDiscoveryService = AutomationBackfillDiscoveryService(lookup, backfillTransaction, backfillRecorder, properties, usage, holds, clock)

    private fun idleOperations(clock: Clock): CaptureOperationService =
        (idleCapture ?: CaptureHarness(idleCaptureRoot, clock).also { idleCapture = it }).operations

    private companion object {
        val LOCK_WAIT_LIMIT: Duration = Duration.ofSeconds(5)
        const val LOCK_POLL_MILLIS = 25L
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Transactions {
        @Bean
        fun automationScanWriter(
            sessions: AutomationSessionRepository,
            items: AutomationItemRepository,
            sweepRuns: AutomationSweepRunRepository,
            mapper: AutomationRecordMapper,
            events: AutomationItemEventWriter,
        ) = AutomationScanWriter(sessions, items, sweepRuns, mapper, events)

        @Bean
        fun automationVariationSeedTransaction(variations: AutomationVariationRepository, mapper: AutomationVariationMapper) =
            AutomationVariationSeedTransaction(variations, mapper)

        @Bean
        fun automationVariationTransaction(variations: AutomationVariationRepository, mapper: AutomationVariationMapper) =
            AutomationVariationTransaction(variations, mapper, AutomationVariationViews(variations, mapper))

        @Bean
        fun automationItemLog() = AutomationItemLog(AutomationFixtures.properties(), Clock.systemUTC())

        @Bean
        fun automationItemEventWriter(events: AutomationItemEventRepository, items: AutomationItemRepository, sweepRuns: AutomationSweepRunRepository, itemLog: AutomationItemLog) =
            AutomationItemEventWriter(events, items, sweepRuns, itemLog)

        @Bean
        fun automationHistoryStartTransaction(items: AutomationItemRepository, mapper: AutomationRecordMapper, events: AutomationItemEventWriter) =
            AutomationHistoryStartTransaction(items, mapper, events)

        @Bean
        fun automationSummaryTransaction(items: AutomationItemRepository) = AutomationSummaryTransaction(items)

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
        fun automationSessionTransaction(sessions: AutomationSessionRepository, items: AutomationItemRepository, mapper: AutomationRecordMapper, events: AutomationItemEventWriter) =
            AutomationSessionTransaction(sessions, items, mapper, events)

        @Bean
        fun automationPipelineTransaction(items: AutomationItemRepository, backfills: AutomationBackfillRepository, mapper: AutomationRecordMapper, events: AutomationItemEventWriter, buyNotifier: AutomationBuyNotifier) =
            AutomationPipelineTransaction(items, backfills, mapper, events, buyNotifier)

        @Bean
        fun switchableNotifications() = SwitchableNotifications()

        @Bean
        fun automationBuyNotifier(sweepRuns: AutomationSweepRunRepository, variations: AutomationVariationRepository, mapper: AutomationVariationMapper, notifications: SwitchableNotifications) =
            AutomationBuyNotifier(sweepRuns, AutomationVariationViews(variations, mapper), notifications.publisher, AutomationFixtures.properties())

        @Bean
        fun automationSweepTransaction(items: AutomationItemRepository, sweepRuns: AutomationSweepRunRepository, mapper: AutomationRecordMapper, events: AutomationItemEventWriter) =
            AutomationSweepTransaction(items, sweepRuns, mapper, events)

        @Bean
        fun automationRetryTransaction(items: AutomationItemRepository, backfills: AutomationBackfillRepository, mapper: AutomationRecordMapper, events: AutomationItemEventWriter) =
            AutomationRetryTransaction(items, backfills, mapper, events)

        @Bean
        fun automationRunCycles(intents: TradeIntentStore, orders: PaperOrderStore, positions: PositionStore, events: WalletEventStore) =
            AutomationRunCycles(intents, orders, positions, events)
    }
}
