package com.trenching.bot.config

import com.trenching.bot.console.config.ConsoleProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
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

    @Test
    fun `shared configuration suggests Decu's public wallet to the capture form`() {
        val loader = YamlPropertiesFactoryBean()
        loader.setResources(ClassPathResource("application.yml"))
        val source = MapConfigurationPropertySource(requireNotNull(loader.getObject()))

        val console = Binder(source).bind(ConsoleProperties.PREFIX, ConsoleProperties::class.java).get()

        assertEquals(listOf(ConsoleProperties.SuggestedWallet("Decu", "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9")), console.suggestedWallets)
        assertFalse(console.enabled)
    }
}
