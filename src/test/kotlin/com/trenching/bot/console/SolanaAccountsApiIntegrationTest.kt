package com.trenching.bot.console

import com.trenching.bot.chaincapture.CaptureBudget
import com.trenching.bot.chaincapture.CaptureRpcs
import com.trenching.bot.chaincapture.LocalRpcServer
import com.trenching.bot.chaincapture.RpcEndpointChoice
import com.trenching.bot.chaincapture.TestSettings
import com.trenching.bot.chaincapture.testMapper
import com.trenching.bot.console.solana.SolanaConsoleHttp
import com.trenching.bot.console.solana.SolanaRestClients
import com.trenching.bot.console.solana.accounts.RpcAccountTestData
import com.trenching.bot.console.solana.accounts.application.RpcAccountDirectory
import com.trenching.bot.console.solana.accounts.domain.BalanceRead
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfig
import com.trenching.bot.console.solana.accounts.domain.RpcAccountConfigs
import com.trenching.bot.console.solana.accounts.domain.RpcAccountName
import com.trenching.bot.console.solana.accounts.application.RpcAccountRefresh
import com.trenching.bot.console.solana.accounts.application.RpcAccountStore
import com.trenching.bot.console.solana.accounts.infrastructure.helius.HeliusUsageClient
import com.trenching.bot.console.solana.accounts.ScriptedNetworkCheck
import com.trenching.bot.console.solana.accounts.TestClock
import com.trenching.bot.config.SimulationClock
import java.time.Duration
import java.time.ZoneOffset
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.junit.jupiter.api.AfterAll
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import com.trenching.bot.console.solana.accounts.infrastructure.persistence.RpcAccountRepository
import com.trenching.bot.notification.LogCapture
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The accounts end to end against PostgreSQL: the table builds from the entity, the directory stores and publishes in one transaction,
 * and the read endpoint never shows an endpoint, key or project id. The accounts are fake hosts and the balance refresh is an hour
 * away, so nothing here can reach a provider.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.application.name=solana-accounts",
        "trenching.console.enabled=true",
        "trenching.solana.rpc-url=${RpcAccountTestData.PRIMARY_URL}",
        "trenching.solana.backup-rpc-url=${RpcAccountTestData.BACKUP_URL}",
        "trenching.solana.usage.primary-project-id=${RpcAccountTestData.PRIMARY_PROJECT}",
        "trenching.solana.usage.backup-project-id=${RpcAccountTestData.BACKUP_PROJECT}",
        "trenching.solana.usage.base-url=http://127.0.0.1:1",
        "trenching.solana.usage.refresh-interval=1h",
        "trenching.notification.telegram.bot-token=123456:FAKE-test-token-value",
        "trenching.notification.telegram.chat-id=-1001234567890",
        "trenching.notification.telegram.api-base-url=http://127.0.0.1:1",
        "trenching.notification.dispatch-interval=1h",
    ],
)
@Tag("integration")
@ActiveProfiles("test")
@ContextConfiguration(initializers = [CaptureStartupLogs::class])
@Import(PostgresTestDatabaseConfiguration::class, SolanaAccountsTestConfiguration::class)
class SolanaAccountsApiIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var directory: RpcAccountDirectory
    @Autowired private lateinit var store: RpcAccountStore
    @Autowired private lateinit var clock: TestClock
    @Autowired private lateinit var network: ScriptedNetworkCheck
    @Autowired private lateinit var repository: RpcAccountRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val http by lazy { SolanaConsoleHttp(port, objectMapper) }

    @BeforeEach
    fun reset() {
        clock.now = clock.now.plus(Duration.ofDays(2))
        RpcAccountName.entries.forEach(directory::answered)
        jdbc.update("delete from rpc_accounts")
        jdbc.update("delete from notification_outbox")
    }

    private fun switchedKeys() = jdbc.queryForList("select notification_key from notification_outbox where kind = 'RPC_ACCOUNT_SWITCHED' order by notification_key", String::class.java).filterNotNull()

    private fun items(node: JsonNode): List<JsonNode> = (0 until node.size()).map { node.get(it) }

    private fun count(sql: String): Int = requireNotNull(jdbc.queryForObject(sql, Int::class.java)) { "count" }

    @Test
    fun `the accounts endpoint lists both accounts with their host and standing and nothing private`() {
        val (status, body) = http.get("/api/solana/accounts")

        assertEquals(200, status)
        assertEquals("primary", body.path("active").asString())
        val accounts = items(body.path("accounts"))
        assertEquals(listOf("primary", "backup"), accounts.map { it.path("name").asString() })
        assertEquals(listOf("ACTIVE", "STANDBY"), accounts.map { it.path("state").asString() })
        assertEquals(listOf("primary.example.test", "backup.example.test"), accounts.map { it.path("host").asString() })
        assertTrue(accounts.all { it.path("configured").asBoolean() })
        assertTrue(accounts.all { it.path("outOfCreditsSince").isNull })
    }

    @Test
    fun `an exhausted primary makes the backup active, is stored once and one switch message is queued`() {
        directory.exhausted("primary")

        val (_, body) = http.get("/api/solana/accounts")
        assertEquals("backup", body.path("active").asString())
        assertEquals(listOf("OUT_OF_CREDITS", "ACTIVE"), items(body.path("accounts")).map { it.path("state").asString() })
        assertNotNull(items(body.path("accounts")).first().path("outOfCreditsSince").asString())
        assertEquals(1, count("select count(*) from rpc_accounts where name = 'primary' and out_of_credits_since is not null"))
        assertEquals(1, switchedKeys().size)
        assertTrue(switchedKeys().single().startsWith("RPC_ACCOUNT_SWITCHED|primary|backup|"))
    }

    @Test
    fun `concurrent reports of the same exhaustion queue one message and store one change`() {
        val callers = 12
        val pool = Executors.newFixedThreadPool(callers)
        val start = CountDownLatch(1)
        try {
            (1..callers).map { pool.submit { start.await(); directory.exhausted("primary") } }.also { start.countDown() }.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, switchedKeys().size)
        assertEquals(1, repository.count())
    }

    @Test
    fun `no response, table or queued message holds a url, key or project id`() {
        directory.exhausted("primary")
        directory.exhausted("backup")

        val everything = http.get("/api/solana/accounts").second.toString() +
            jdbc.queryForList("select * from rpc_accounts").toString() +
            jdbc.queryForList("select notification_key, payload from notification_outbox").toString()

        listOf(RpcAccountTestData.PRIMARY_KEY, RpcAccountTestData.BACKUP_KEY, RpcAccountTestData.PRIMARY_PROJECT, RpcAccountTestData.BACKUP_PROJECT, "api-key", "https://").forEach {
            assertFalse(everything.contains(it), "found $it")
        }
        assertTrue(http.get("/api/solana/accounts").second.path("active").isNull)
    }

    @Test
    fun `the status probe still answers while the active account is the backup`() {
        directory.exhausted("primary")

        val (status, body) = http.get("/api/solana/status")

        assertEquals(200, status)
        assertEquals(true, body.path("configured").asBoolean())
        assertEquals("backup.example.test", body.path("host").asString())
    }

    @Test
    fun `a restart while on the backup keeps it, without a second message`() {
        directory.exhausted("primary")

        val restarted = RpcAccountDirectory(RpcAccountTestData.both(), store.load(), store, ScriptedNetworkCheck(), Clock.systemUTC(), 10, 1_000, ZoneOffset.UTC)

        assertEquals(RpcEndpointChoice.OutOfCredits, restarted.choose())
        restarted.verifyNetworks()
        assertEquals("backup", (restarted.choose() as RpcEndpointChoice.Use).target.account)
        assertEquals(1, switchedKeys().size)
    }

    @Test
    fun `the table keeps one row per account name`() {
        RpcAccountDirectory(RpcAccountTestData.both(), emptyList(), store, ScriptedNetworkCheck(), Clock.systemUTC(), 10, 1_000, ZoneOffset.UTC)
            .recordBalance(RpcAccountName.PRIMARY, BalanceRead.Read(RpcAccountTestData.balance(500_000)))

        val row = repository.findById("primary").orElseThrow()

        assertEquals(500_000L, row.creditsRemaining)
        assertEquals("Free", row.plan)
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("insert into rpc_accounts (name, updated_at) values ('primary', now())")
        }
    }

    @Test
    fun `a call whose primary says max usage reached completes on the backup and the state follows`() {
        LocalRpcServer { LocalRpcServer.reply(it, 429, """{"error":{"code":429,"message":"max usage reached"}}""") }.use { primary ->
            LocalRpcServer { LocalRpcServer.reply(it, 200, """{"jsonrpc":"2.0","id":1,"result":"genesis"}""") }.use { backup ->
                val configs = RpcAccountConfigs(listOf(
                    RpcAccountConfig(RpcAccountName.PRIMARY, primary.endpoint, "127.0.0.1", null),
                    RpcAccountConfig(RpcAccountName.BACKUP, backup.endpoint, "127.0.0.1", null),
                ))
                val real = RpcAccountDirectory(configs, store.load(), store, ScriptedNetworkCheck(), Clock.systemUTC(), 10, 1_000, ZoneOffset.UTC)
                val rpc = CaptureRpcs.create(TestSettings(endpoint = primary.endpoint), testMapper, Clock.systemUTC(), endpoints = real)

                val result = rpc.call("getGenesisHash", emptyList(), CaptureBudget(10, 100_000))

                assertEquals("genesis", result.asString())
                assertIs<RpcEndpointChoice.Use>(real.choose())
                assertEquals("backup", (real.choose() as RpcEndpointChoice.Use).target.account)
                assertEquals(1, count("select count(*) from rpc_accounts where name = 'primary' and out_of_credits_since is not null"))
                assertEquals(1, switchedKeys().size)
            }
        }
    }

    @Test
    fun `startup and the accounts endpoint log no key`() {
        directory.exhausted("primary")
        http.get("/api/solana/accounts")

        val startup = requireNotNull(StartupLogs.capture) { "the capture began with the context" }
        assertTrue(startup.events.isNotEmpty(), "the capture holds the logs written while the context started")
        assertFalse(startup.everything().contains(RpcAccountTestData.PRIMARY_KEY))
        assertFalse(startup.everything().contains(RpcAccountTestData.BACKUP_KEY))
        assertFalse(startup.everything().contains(RpcAccountTestData.PRIMARY_PROJECT))
        assertFalse(startup.everything().contains(RpcAccountTestData.BACKUP_PROJECT))
    }

    @Test
    fun `after a balance read the endpoint shows credits, limit, cycle end and plan, and an account without a project id shows its problem`() {
        LocalRpcServer {
            LocalRpcServer.reply(it, 200, """{"creditsRemaining":640000.9,"prepaidCreditsRemaining":10,"creditsUsed":359999,"creditCycle":{"start":"2026-11-05","end":"2026-12-05"},"subscriptionDetails":{"creditsLimit":1000000,"plan":"Developer"}}""")
        }.use { admin ->
            val configs = RpcAccountConfigs(listOf(RpcAccountTestData.primary(), RpcAccountTestData.backup(projectId = null)))
            val balances = HeliusUsageClient(configs, admin.endpoint, SolanaRestClients.create(Duration.ofSeconds(5), Duration.ofSeconds(5)), objectMapper)

            RpcAccountRefresh(configs, directory, balances, { false }, clock).refresh()
        }

        val accounts = items(http.get("/api/solana/accounts").second.path("accounts"))
        val primary = accounts.first { it.path("name").asString() == "primary" }
        assertEquals(640_010L, primary.path("creditsRemaining").asLong())
        assertEquals(359_999L, primary.path("creditsUsed").asLong())
        assertEquals(1_000_000L, primary.path("creditsLimit").asLong())
        assertEquals("2026-12-05", primary.path("cycleEndsOn").asString())
        assertEquals("Developer", primary.path("plan").asString())
        assertTrue(primary.path("balanceProblem").isNull)
        assertNotNull(primary.path("balanceCheckedAt").asString())
        val backup = accounts.first { it.path("name").asString() == "backup" }
        assertEquals("NO_PROJECT_ID", backup.path("balanceProblem").asString())
        assertTrue(backup.path("creditsRemaining").isNull)
    }

    @Test
    fun `a low balance read twice in one cycle queues one message in the real outbox`() {
        val low = BalanceRead.Read(RpcAccountTestData.balance(remaining = 50_000))

        directory.recordBalance(RpcAccountName.PRIMARY, low)
        clock.now = clock.now.plusSeconds(900)
        directory.recordBalance(RpcAccountName.PRIMARY, low)

        assertEquals(1, count("select count(*) from notification_outbox where kind = 'RPC_BALANCE_LOW'"))
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun stopCapturing() {
            StartupLogs.capture?.close()
            StartupLogs.capture = null
        }
    }
}

/** Where [CaptureStartupLogs] keeps what it captured; the logging system is configured before an initializer runs, so nothing it writes later removes the capture. */
object StartupLogs {
    @Volatile var capture: LogCapture? = null
}

/** Starts capturing logs before any bean is created, so what startup writes is checked too. */
class CaptureStartupLogs : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        StartupLogs.capture = LogCapture()
    }
}

/** A clock the test moves and a network check it controls, so the real directory is exercised without waiting or reaching a provider. */
@TestConfiguration(proxyBeanMethods = false)
class SolanaAccountsTestConfiguration {
    @Bean
    @Primary
    @Qualifier(SimulationClock.NAME)
    fun testClock(): TestClock = TestClock()

    @Bean
    @Primary
    fun scriptedNetworkCheck(): ScriptedNetworkCheck = ScriptedNetworkCheck()
}
