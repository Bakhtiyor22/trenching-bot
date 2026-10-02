package com.trenching.bot

import com.trenching.bot.support.PostgresTestDatabaseConfiguration
import javax.sql.DataSource
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@Tag("integration")
@ActiveProfiles("test")
@Import(PostgresTestDatabaseConfiguration::class)
class TrenchingBotApplicationTests {

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `application context loads`() {
        // Fails the build if any bean definition or configuration property is broken.
    }

    @Test
    fun `datasource connects to postgresql`() {
        dataSource.connection.use { connection ->
            assertEquals("PostgreSQL", connection.metaData.databaseProductName)

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { rows ->
                    check(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
            }
        }
    }
}
