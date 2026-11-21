package com.trenching.bot.config.schema

import com.trenching.bot.common.LogLine
import java.sql.Connection
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.SessionFactoryObserver
import org.hibernate.boot.Metadata
import org.hibernate.boot.spi.BootstrapContext
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.integrator.spi.Integrator
import org.slf4j.LoggerFactory

/**
 * Runs the repair as the last step of building the session factory: Hibernate's own schema update has added what it can by
 * then, and nothing has been handed the factory yet, so no write can meet a stale constraint first. It uses the factory's own
 * connection, in one transaction, so it always repairs the database the entities were just applied to. A statement that fails
 * rolls every repair back and stops the start; each repair is reported only once the transaction has committed.
 */
class SchemaDriftIntegrator(private val repair: SchemaDriftRepair) : Integrator {
    private val log = LoggerFactory.getLogger(SchemaDriftIntegrator::class.java)

    override fun integrate(metadata: Metadata, bootstrapContext: BootstrapContext, sessionFactory: SessionFactoryImplementor) {
        sessionFactory.addObserver(object : SessionFactoryObserver {
            override fun sessionFactoryCreated(factory: SessionFactory) {
                val mapped = MappedColumns.of(metadata)
                val repairs = factory.fromTransaction { session: Session ->
                    session.doReturningWork { connection: Connection -> repair.repair(connection, mapped) }
                }
                repairs.forEach {
                    LogLine("Database schema repaired: a leftover constraint from an older build was relaxed to match the code.").field("table", it.table)
                        .field("column", it.column).field("relaxed", it.relaxed).field("added", it.added.joinToString(",").ifEmpty { null }).warn(log)
                }
            }
        })
    }
}
