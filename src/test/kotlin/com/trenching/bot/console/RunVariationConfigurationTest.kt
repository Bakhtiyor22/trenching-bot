package com.trenching.bot.console

import com.trenching.bot.console.application.RunVariationLabels
import com.trenching.bot.console.config.RunVariationConfiguration
import com.trenching.bot.console.domain.RunVariationLookup
import com.trenching.bot.support.FakeRunVariationLookup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class RunVariationConfigurationTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(RunVariationConfiguration::class.java)

    @Test
    fun `a lookup bean, as the automation console registers, is the one the labels use`() {
        runner.withUserConfiguration(Lookup::class.java).run { context ->
            assertThat(context.getBean(RunVariationLabels::class.java).of("run-1")).containsExactly("V03")
        }
    }

    @Test
    fun `without a lookup bean every run has no variation`() {
        runner.run { context ->
            assertThat(context.getBean(RunVariationLabels::class.java).of(listOf("run-1"))).isEqualTo(mapOf("run-1" to emptyList<String>()))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Lookup {
        @Bean
        fun lookup() = FakeRunVariationLookup { ids -> ids.associateWith { listOf("V03") } }
    }
}
