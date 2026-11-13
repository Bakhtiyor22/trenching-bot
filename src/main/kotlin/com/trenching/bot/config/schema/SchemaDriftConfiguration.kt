package com.trenching.bot.config.schema

import org.hibernate.jpa.boot.spi.IntegratorProvider
import org.hibernate.jpa.boot.spi.JpaSettings
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Active wherever Hibernate maintains the schema itself (`ddl-auto: update`, the `local` and `test` profiles). Production only
 * validates and its schema is changed by hand, so nothing is repaired there.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["spring.jpa.hibernate.ddl-auto"], havingValue = "update")
class SchemaDriftConfiguration {
    @Bean
    fun schemaDriftIntegration(): HibernatePropertiesCustomizer = HibernatePropertiesCustomizer { properties ->
        properties[JpaSettings.INTEGRATOR_PROVIDER] = IntegratorProvider { listOf(SchemaDriftIntegrator(SchemaDriftRepair())) }
    }
}
