package com.trenching.bot.config.schema

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SchemaDriftConfigurationTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(SchemaDriftConfiguration::class.java)

    @Test
    fun `the repair is registered where Hibernate updates the schema itself`() {
        runner.withPropertyValues("$DDL_AUTO=update").run { context ->
            assertThat(context).hasSingleBean(HibernatePropertiesCustomizer::class.java)
        }
    }

    @Test
    fun `any other schema mode, or none, registers no repair`() {
        listOf("validate", "none", "create", "create-drop").forEach { mode ->
            runner.withPropertyValues("$DDL_AUTO=$mode").run { context ->
                assertThat(context).doesNotHaveBean(HibernatePropertiesCustomizer::class.java)
            }
        }
        runner.run { context -> assertThat(context).doesNotHaveBean(HibernatePropertiesCustomizer::class.java) }
    }

    @Test
    fun `the production profile validates, so nothing is repaired there`() {
        runner.withInitializer(ConfigDataApplicationContextInitializer()).withPropertyValues("spring.profiles.active=prod").run { context ->
            assertThat(context.environment.getProperty(DDL_AUTO)).isEqualTo("validate")
            assertThat(context).doesNotHaveBean(HibernatePropertiesCustomizer::class.java)
        }
    }

    private companion object {
        const val DDL_AUTO = "spring.jpa.hibernate.ddl-auto"
    }
}
