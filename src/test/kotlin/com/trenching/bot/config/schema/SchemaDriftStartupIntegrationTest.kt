package com.trenching.bot.config.schema

import com.trenching.bot.TrenchingBotApplication
import com.trenching.bot.replay.ReplayDatasetCatalog
import com.trenching.bot.replay.SimulationRuns
import com.trenching.bot.replay.application.PersistedReplayService
import com.trenching.bot.replay.domain.RunStorage
import com.trenching.bot.replay.domain.SampleDataset
import com.trenching.bot.strategy.domain.SimulationConfig
import com.trenching.bot.support.DipSample
import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import com.trenching.bot.support.TestComponentExcludeFilter
import com.trenching.bot.support.WalletSellDipSample
import java.math.BigDecimal
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ActiveProfiles

/**
 * Every other integration test builds its schema from the current entities. These start the whole application on a schema aged
 * back to what engine `dip-paper-v4` left behind (its enum checks and a NOT NULL since dropped from the entities), which is the
 * state a long-lived local database is in.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(PostgresTestDatabaseConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class SchemaDriftStartupIntegrationTest {
    @Autowired private lateinit var server: JdbcConnectionDetails
    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `an application started on a database an older release built relaxes its stale constraints and stores what they rejected`(output: CapturedOutput) {
        scratch(UPGRADED) { database ->
            start(UPGRADED).use { application ->
                javaClass.getResourceAsStream(SAMPLE).use { sample ->
                    application.getBean(PersistedReplayService::class.java).execute("dip-sample-v3.json", requireNotNull(sample), SimulationConfig.mvp())
                }
            }
            assertEquals(0, repairs(output), "a database built from the current entities needs no repair")
            val current = definitions(database)
            BEFORE_DIP_PAPER_V5.forEach(database::execute)

            start(UPGRADED).use { application ->
                val run = application.getBean(PersistedReplayService::class.java).execute(WalletSellDipSample.dataset(), SimulationConfig.agreed())
                val datasetId = application.getBean(ReplayDatasetCatalog::class.java).importSample(SampleDataset.DIP_SAMPLE_V3).dataset.id
                val reference = application.getBean(SimulationRuns::class.java).start(datasetId, REFERENCE_CONFIG, RunStorage.DATASET_REFERENCE)

                assertEquals(listOf(REFERENCE_UNAVAILABLE, WALLET_SOLD), database.queryForList(
                    "select distinct watch from trade_intents where run_id = ? and watch in ('$WALLET_SOLD', '$REFERENCE_UNAVAILABLE') order by 1",
                    String::class.java, run.id))
                assertEquals(listOf(REFERENCE_UNAVAILABLE, WALLET_SOLD), database.queryForList(
                    "select distinct o.rejection_reason from paper_orders o join trade_intents i on i.id = o.id " +
                        "where i.run_id = ? and o.rejection_reason in ('$WALLET_SOLD', '$REFERENCE_UNAVAILABLE') order by 1", String::class.java, run.id))
                assertEquals(mapOf("close_reason" to WALLET_SOLD, "exit_trigger_reason" to WALLET_SOLD), database.queryForMap(
                    "select close_reason, exit_trigger_reason from positions where run_id = ? and base_mint = ?",
                    run.id, DipSample.mint(DipSample.STOP_LOSS).value))
                assertTrue(reference.created)
                assertEquals(1, database.queryForObject("select count(*) from simulation_runs where id = ? and dataset_snapshot is null",
                    Int::class.java, reference.run.id))
            }
            val repaired = definitions(database)
            STALE_CHECKS.forEach { (table, column, values) ->
                val name = "${table}_${column}_check"
                assertTrue(repaired.getValue(name).endsWith(" NOT VALID"), "$name is added without re-checking old rows: ${repaired.getValue(name)}")
                assertEquals(allowed(current.getValue(name), column) + listed(values), allowed(repaired.getValue(name), column),
                    "$name allows what a fresh database allows and everything the old check allowed")
            }
            assertEquals(STALE_CHECKS.size + 1, repairs(output), "each stale check and the left-over NOT NULL, once")
            start(UPGRADED).close()
            assertEquals(STALE_CHECKS.size + 1, repairs(output), "a repaired database is left alone on the next start")
            assertEquals(LEGACY_ROWS, database.queryForObject("select count(*) from paper_orders where rejection_reason = '$DROPPED_REASON'", Int::class.java),
                "rows the old check accepted are kept")
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    fun `a repair that cannot lock its table in time stops the start and rolls back the repairs made before it`(output: CapturedOutput) {
        scratch(FAILING) { database ->
            start(FAILING).close()
            BEFORE_DIP_PAPER_V5.forEach(database::execute)
            val aged = definitions(database)

            // A share lock lets Hibernate read the catalog but makes the repair's ALTER TABLE on positions wait past its lock timeout.
            DriverManager.getConnection(url(FAILING), server.username.orEmpty(), server.password.orEmpty()).use { lock ->
                lock.autoCommit = false
                lock.createStatement().use { it.execute("lock table positions in access share mode") }
                val failure = assertFails { start(FAILING).close() }
                lock.rollback()
                val repairFailure = generateSequence(failure) { it.cause }.filterIsInstance<SchemaDriftRepairException>().firstOrNull()
                assertTrue(repairFailure?.message.orEmpty().endsWith("reason=${SchemaDriftRepairException.TABLE_LOCKED}"), "the start fails on the locked table: $failure")
            }
            assertEquals(aged, definitions(database), "the paper_orders repair that ran before the locked positions table is rolled back")
            assertEquals(0, repairs(output), "nothing is reported as repaired")
        }
    }

    @Test
    fun `on a real catalog, unnamed checks are named as the repair expects and key columns keep their not null`() {
        scratch(CATALOG) { database ->
            database.execute("create table $LONG_TABLE ($LONG_COLUMN varchar(8) check ($LONG_COLUMN in ('A')) check ($LONG_COLUMN in ('A', 'B')))")
            val names = database.queryForList("select conname from pg_constraint where conrelid = '$LONG_TABLE'::regclass", String::class.java).filterNotNull()

            assertEquals(2, names.size, "a check and a clash: $names")
            assertTrue(names.all { GeneratedCheckName.matches(it, LONG_TABLE, LONG_COLUMN) }, "names: $names")

            database.execute("create table $KEY_PROBE (id bigint primary key, seq bigint generated always as identity, plain varchar(8) not null)")
            val mapped = listOf("id", "seq", "plain").map { MappedColumn(KEY_PROBE, it, nullable = true, membershipCheck = null) }
            val repairs = DriverManager.getConnection(url(CATALOG), server.username.orEmpty(), server.password.orEmpty()).use { connection ->
                connection.autoCommit = false
                SchemaDriftRepair().repair(connection, mapped).also { connection.commit() }
            }

            assertEquals(listOf("plain"), repairs.map { it.column }, "the primary key and the identity column are left alone")
            assertEquals(mapOf("id" to "NO", "seq" to "NO", "plain" to "YES"), database
                .queryForList("select column_name, is_nullable from information_schema.columns where table_name = '$KEY_PROBE'")
                .associate { it.getValue("column_name") as String to it.getValue("is_nullable") as String })
        }
    }

    /** A scratch database on the test server, dropped before and after so a rerun in the same JVM starts clean. */
    private fun scratch(name: String, test: (JdbcTemplate) -> Unit) {
        val admin = JdbcTemplate(dataSource)
        admin.execute("drop database if exists $name with (force)")
        admin.execute("create database $name")
        try {
            test(JdbcTemplate(DriverManagerDataSource(url(name), server.username.orEmpty(), server.password.orEmpty())))
        } finally {
            admin.execute("drop database if exists $name with (force)")
        }
    }

    /**
     * The whole application on [database]. `application.yml` activates the `local` profile unless something of higher precedence
     * names another, and `local` points at the developer's own database with automation on, so the profile and the datasource
     * are given as command-line arguments, and the context refuses to refresh on any other database.
     */
    private fun start(database: String, vararg arguments: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(TrenchingBotApplication::class.java)
            .web(WebApplicationType.NONE)
            .initializers(ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                check(context.environment.activeProfiles.toList() == listOf(PROFILE)) { "Unexpected profiles: ${context.environment.activeProfiles.toList()}" }
                check(context.environment.getProperty(DATASOURCE_URL) == url(database)) { "The application under test must use the scratch database" }
                context.beanFactory.registerSingleton("testComponentExcludeFilter", TestComponentExcludeFilter())
            })
            .run("--spring.profiles.active=$PROFILE", "--$DATASOURCE_URL=${url(database)}", "--spring.datasource.username=${server.username.orEmpty()}",
                "--spring.datasource.password=${server.password.orEmpty()}", *arguments)

    private fun url(database: String): String = server.jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/" + database

    private fun repairs(output: CapturedOutput): Int = output.out.lineSequence().count { REPAIRED in it }

    private fun allowed(definition: String, column: String): Set<String> = requireNotNull(MembershipCheck.storedValues(definition, column)) { definition }

    private fun listed(values: String): Set<String> = values.split(',').map { it.trim().removeSurrounding("'") }.toSet()

    /** The current definition of each check [BEFORE_DIP_PAPER_V5] ages, as PostgreSQL prints it. */
    private fun definitions(database: JdbcTemplate): Map<String, String> = STALE_CHECKS.associate { (table, column, _) ->
        val name = "${table}_${column}_check"
        name to requireNotNull(database.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conname = ?", String::class.java, name))
    }

    private companion object {
        const val UPGRADED = "schema_drift_startup"
        const val FAILING = "schema_drift_failure"
        const val CATALOG = "schema_drift_catalog"
        const val KEY_PROBE = "key_probe"
        const val PROFILE = "test"
        const val REPAIRED = "schema drift repaired"
        const val DATASOURCE_URL = "spring.datasource.url"
        const val SAMPLE = "/replay/samples/dip-sample-v3.json"
        const val WALLET_SOLD = "WALLET_SOLD"
        const val REFERENCE_UNAVAILABLE = "REFERENCE_UNAVAILABLE"
        const val DROPPED_REASON = "ALREADY_OPEN"
        const val LEGACY_ROWS = 1
        const val LONG_TABLE = "automation_backfill_scan_checkpoint_history_entries"
        const val LONG_COLUMN = "discovered_wallet_activity_signature_cursor"
        const val V4_WATCHES = "'TRIGGERED','NO_DIP','DIP_BELOW_MIN_MARKET_CAP','REFERENCE_BELOW_MIN_MARKET_CAP','MIGRATED_BEFORE_ENTRY'," +
            "'UNSUPPORTED_SUPPLY'"
        const val V4_REJECTIONS = "'MISSING_OBSERVATION','STALE_OBSERVATION','INVALID_QUALITY','INSUFFICIENT_LIQUIDITY','ALREADY_OPEN'," +
            "'ZERO_QUANTITY','NO_DIP','DIP_BELOW_MIN_MARKET_CAP','REFERENCE_BELOW_MIN_MARKET_CAP','MIGRATED_BEFORE_ENTRY','UNSUPPORTED_SUPPLY'," +
            "'NO_FILL_AFTER_TRIGGER','EXCEEDS_CURVE','HORIZON_REACHED','MIGRATED','NO_EXIT_FILL','UNRESOLVED'"
        const val V4_CLOSES = "'TAKE_PROFIT','STOP_LOSS','TIME_EXIT'"

        /** Table, column and the values its check listed before engine `dip-paper-v5` added `WALLET_SOLD` and the reasons after it. */
        val STALE_CHECKS: List<Triple<String, String, String>> = listOf(
            Triple("trade_intents", "watch", V4_WATCHES),
            Triple("paper_orders", "rejection_reason", V4_REJECTIONS),
            Triple("positions", "exit_unavailable", V4_REJECTIONS),
            Triple("positions", "close_reason", V4_CLOSES),
            Triple("positions", "exit_trigger_reason", V4_CLOSES),
        )

        /**
         * What `ddl-auto: update` never revisits on such a database: the enum checks as created then, the NOT NULL that
         * dataset-referenced runs later dropped from the entity, and a row holding a reason the enum no longer has.
         */
        val BEFORE_DIP_PAPER_V5: List<String> = STALE_CHECKS.map { (table, column, values) ->
            "alter table $table drop constraint ${table}_${column}_check, add constraint ${table}_${column}_check check ($column in ($values))"
        } + listOf(
            "alter table simulation_runs alter column dataset_snapshot set not null",
            "update paper_orders set rejection_reason = '$DROPPED_REASON' where rejection_reason = 'NO_DIP'",
        )

        val REFERENCE_CONFIG: SimulationConfig =
            SimulationConfig.of(BigDecimal("0.1"), 2, 1500, 3000, 1500, BigDecimal("10000"), 4500, 1, BigDecimal("0.0013"))
    }
}
