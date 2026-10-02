package com.trenching.bot.config

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource

class ApplicationProfileTest {

    @Test
    fun `shared configuration defaults to local without embedding datasource settings`() {
        val loader = YamlPropertiesFactoryBean()
        loader.setResources(ClassPathResource("application.yml"))
        val properties = requireNotNull(loader.getObject())

        assertEquals("local", properties.getProperty("spring.profiles.active"))
        assertFalse(properties.containsKey("spring.profiles.default"))
        assertTrue(properties.keys.none { it.toString().startsWith("spring.datasource.") })
    }
}
