package com.trenching.bot.console.monitoring

import com.trenching.bot.console.domain.HealthSnapshot
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.stereotype.Component

/**
 * Reads the aggregate Actuator serves at `/actuator/health`. The endpoint bean is optional because
 * non-web contexts such as the persisted-replay command may not register it.
 */
@Component
class ActuatorApplicationHealth(private val healthEndpoint: ObjectProvider<HealthEndpoint>) : ApplicationHealth {
    override fun current(): HealthSnapshot {
        val health = healthEndpoint.ifAvailable?.health() ?: return HealthSnapshot(UNKNOWN, null)
        val database = (health as? CompositeHealthDescriptor)?.components?.get(DATABASE_COMPONENT)?.status?.code
        return HealthSnapshot(health.status.code, database)
    }

    private companion object {
        const val DATABASE_COMPONENT = "db"
        const val UNKNOWN = "UNKNOWN"
    }
}
