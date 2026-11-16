package com.trenching.bot.console.automation.api

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Profiles
import org.springframework.core.env.StandardEnvironment

class AutomationHistoryStartRunnerProfileTest {
    @Test
    fun `the history start runs under local and prod, never with test`() {
        val profiles = Profiles.of(*requireNotNull(AutomationHistoryStartRunner::class.java.getAnnotation(Profile::class.java)).value)

        assertTrue(accepts(profiles, "local"))
        assertTrue(accepts(profiles, "prod"))
        assertFalse(accepts(profiles, "test"))
        assertFalse(accepts(profiles, "prod", "test"))
    }

    private fun accepts(profiles: Profiles, vararg active: String): Boolean {
        val environment = StandardEnvironment()
        environment.setActiveProfiles(*active)
        return environment.acceptsProfiles(profiles)
    }
}
