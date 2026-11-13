package com.trenching.bot.config.schema

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class SchemaDriftTest {
    @Test
    fun `a check written before the enum grew is widened to the mapped values, keeping the ones it allowed, without re-checking stored rows`() {
        val repairs = SchemaDrift.repairs(listOf(WATCH), stored(watchCheck(CREATED_BY_HIBERNATE)))

        assertEquals(listOf(SchemaRepair("trade_intents", "watch", "trade_intents_watch_check",
            "alter table \"trade_intents\" drop constraint \"trade_intents_watch_check\", " +
                "add constraint \"trade_intents_watch_check\" check (\"watch\" in ('TRIGGERED','NO_DIP','WALLET_SOLD','DIP_BELOW_MIN_MARKET_CAP')) not valid",
            listOf("WALLET_SOLD"))), repairs)
    }

    @Test
    fun `two builds that map different constants on one database converge instead of undoing each other`() {
        val other = WATCH.copy(membershipCheck = "watch in ('TRIGGERED','NO_DIP','REFERENCE_UNAVAILABLE')")
        val afterThis = "CHECK (((watch)::text = ANY ((ARRAY['TRIGGERED'::character varying, 'NO_DIP'::character varying, " +
            "'WALLET_SOLD'::character varying, 'DIP_BELOW_MIN_MARKET_CAP'::character varying])::text[]))) NOT VALID"

        val byOther = SchemaDrift.repairs(listOf(other), stored(watchCheck(afterThis))).single()

        assertEquals(listOf("REFERENCE_UNAVAILABLE"), byOther.added)
        assertEquals(setOf("TRIGGERED", "NO_DIP", "REFERENCE_UNAVAILABLE", "WALLET_SOLD", "DIP_BELOW_MIN_MARKET_CAP"),
            MembershipCheck.mappedValues(byOther.sql.substringAfter("check (").substringBefore(") not valid")))
        val afterOther = "CHECK (((watch)::text = ANY ((ARRAY['TRIGGERED'::character varying, 'NO_DIP'::character varying, " +
            "'REFERENCE_UNAVAILABLE'::character varying, 'WALLET_SOLD'::character varying, 'DIP_BELOW_MIN_MARKET_CAP'::character varying])::text[]))) NOT VALID"
        assertEquals(emptyList(), SchemaDrift.repairs(listOf(WATCH), stored(watchCheck(afterOther))), "this build finds nothing left to add")
    }

    @Test
    fun `the check is read the same way after a dump and restore rewrote it`() {
        val repairs = SchemaDrift.repairs(listOf(WATCH), stored(watchCheck(AFTER_RESTORE)))

        assertEquals(listOf("trade_intents_watch_check"), repairs.map { it.relaxed })
    }

    @Test
    fun `a check an earlier repair added not valid is replaced again when the enum grows once more`() {
        listOf(CREATED_BY_HIBERNATE, AFTER_RESTORE).forEach { definition ->
            val repairs = SchemaDrift.repairs(listOf(WATCH), stored(watchCheck("$definition NOT VALID")))

            assertEquals(listOf("trade_intents_watch_check"), repairs.map { it.relaxed }, definition)
        }
    }

    @Test
    fun `a check over a text column or a quoted column is read like the varchar form`() {
        assertEquals(setOf("TRIGGERED", "NO_DIP"), MembershipCheck.storedValues("CHECK ((watch = ANY (ARRAY['TRIGGERED'::text, 'NO_DIP'::text])))", "watch"))
        assertEquals(setOf("TRIGGERED", "NO_DIP"), MembershipCheck.storedValues(
            "CHECK (((\"Watch\")::text = ANY ((ARRAY['TRIGGERED'::character varying, 'NO_DIP'::character varying])::text[])))", "Watch"))
    }

    @Test
    fun `a single-value check is read like a list`() {
        val repairs = SchemaDrift.repairs(listOf(WATCH), stored(watchCheck("CHECK (((watch)::text = 'TRIGGERED'::text))")))

        assertEquals(listOf("trade_intents_watch_check"), repairs.map { it.relaxed })
    }

    @Test
    fun `a check that allows every mapped value is kept, also when it lists values the enum dropped or was relaxed before`() {
        val current = "CHECK (((watch)::text = ANY ((ARRAY['TRIGGERED'::character varying, 'NO_DIP'::character varying, " +
            "'WALLET_SOLD'::character varying, 'DIP_BELOW_MIN_MARKET_CAP'::character varying])::text[])))"

        listOf(current, "$current NOT VALID").forEach { definition ->
            assertEquals(setOf("TRIGGERED", "NO_DIP", "WALLET_SOLD", "DIP_BELOW_MIN_MARKET_CAP"), MembershipCheck.storedValues(definition, "watch"),
                "kept because it was read, not because it was skipped as unknown")
            assertEquals(emptyList(), SchemaDrift.repairs(listOf(WATCH), stored(watchCheck(definition))))
        }
    }

    @Test
    fun `only a check PostgreSQL named for the column is replaced, also when it carries a clash number`() {
        val numbered = watchCheck(CREATED_BY_HIBERNATE).copy(name = "trade_intents_watch_check1")
        val named = watchCheck(CREATED_BY_HIBERNATE).copy(name = "ck_trade_intents_watch")

        val repairs = SchemaDrift.repairs(listOf(WATCH), stored(watchCheck(CREATED_BY_HIBERNATE), numbered, named))

        assertEquals(listOf("trade_intents_watch_check", "trade_intents_watch_check1"), repairs.map { it.relaxed })
    }

    @Test
    fun `a rule written on purpose is never widened, such as paper fills accepting only FILLED`() {
        val orderStatus = MappedColumn("paper_fills", "order_status", nullable = false, membershipCheck = "order_status in ('FILLED','REJECTED')")
        val filledOnly = StoredColumnCheck("paper_fills", "order_status", "ck_paper_fills_status", "CHECK (((order_status)::text = 'FILLED'::text))")

        assertEquals(emptyList(), SchemaDrift.repairs(listOf(orderStatus), stored(filledOnly)))
    }

    @Test
    fun `a check that is anything but a membership list over the column is left alone`() {
        val checks = listOf(
            "CHECK (((watch)::text <> ALL ((ARRAY['TRIGGERED'::character varying])::text[])))",
            "CHECK ((((watch)::text = 'TRIGGERED'::text) OR (watch IS NULL)))",
            "CHECK ((length((watch)::text) > 3))",
            "CHECK (((watch)::text = 'TRIGGERED'::text)) NO INHERIT",
        ).map(::watchCheck)

        assertEquals(emptyList(), SchemaDrift.repairs(listOf(WATCH), StoredConstraints(checks, emptySet())))
    }

    @Test
    fun `a column without a mapped membership check keeps its stored checks`() {
        val notional = MappedColumn("trade_intents", "notional", nullable = false, membershipCheck = null)
        val positive = StoredColumnCheck("trade_intents", "notional", "trade_intents_notional_check", "CHECK ((notional > (0)::numeric))")

        assertEquals(emptyList(), SchemaDrift.repairs(listOf(notional), stored(positive)))
    }

    @Test
    fun `a column the entities map nullable loses a not null left from before`() {
        val snapshot = MappedColumn("simulation_runs", "dataset_snapshot", nullable = true, membershipCheck = null)
        val id = MappedColumn("simulation_runs", "id", nullable = false, membershipCheck = null)
        val notNull = setOf(ColumnId("simulation_runs", "dataset_snapshot"), ColumnId("simulation_runs", "id"),
            ColumnId("unmapped_table", "dataset_snapshot"))

        val repairs = SchemaDrift.repairs(listOf(snapshot, id), StoredConstraints(emptyList(), notNull))

        assertEquals(listOf(SchemaRepair("simulation_runs", "dataset_snapshot", "NOT_NULL",
            "alter table \"simulation_runs\" alter column \"dataset_snapshot\" drop not null", listOf("NULL"))), repairs)
    }

    @Test
    fun `a key or identity column keeps its not null even where the entities map it nullable`() {
        val formerId = MappedColumn("simulation_runs", "legacy_id", nullable = true, membershipCheck = null)
        val column = setOf(ColumnId("simulation_runs", "legacy_id"))

        assertEquals(emptyList(), SchemaDrift.repairs(listOf(formerId), StoredConstraints(emptyList(), notNullColumns = column, keyColumns = column)))
    }

    @Test
    fun `checks of other tables and columns are ignored`() {
        val otherTable = watchCheck(CREATED_BY_HIBERNATE).copy(table = "paper_orders", name = "paper_orders_watch_check")
        val otherColumn = StoredColumnCheck("trade_intents", "state", "trade_intents_state_check",
            "CHECK (((state)::text = ANY ((ARRAY['TRIGGERED'::character varying])::text[])))")

        assertEquals(emptyList(), SchemaDrift.repairs(listOf(WATCH), StoredConstraints(listOf(otherTable, otherColumn), emptySet())))
    }

    private fun stored(vararg checks: StoredColumnCheck) = StoredConstraints(checks.toList(), emptySet())

    private fun watchCheck(definition: String) = StoredColumnCheck("trade_intents", "watch", "trade_intents_watch_check", definition)

    private companion object {
        /** `DipWatchOutcome` cut down to a constant from before `dip-paper-v5`, one it dropped the check for, and one it added. */
        val WATCH = MappedColumn("trade_intents", "watch", nullable = true, membershipCheck = "watch in ('TRIGGERED','NO_DIP','WALLET_SOLD')")

        /** As `pg_get_constraintdef` prints the check Hibernate created. */
        const val CREATED_BY_HIBERNATE = "CHECK (((watch)::text = ANY ((ARRAY['TRIGGERED'::character varying, 'NO_DIP'::character varying, " +
            "'DIP_BELOW_MIN_MARKET_CAP'::character varying])::text[])))"

        /** The same check after `pg_dump` and `pg_restore`. */
        const val AFTER_RESTORE = "CHECK (((watch)::text = ANY (ARRAY[('TRIGGERED'::character varying)::text, ('NO_DIP'::character varying)::text, " +
            "('DIP_BELOW_MIN_MARKET_CAP'::character varying)::text])))"
    }
}
