package com.trenching.bot.console.config.web

import com.trenching.bot.console.config.ConsoleProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsoleProperties::class)
class ConsoleWebConfiguration(private val properties: ConsoleProperties) : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Reserve the path when disabled so Boot's default static mapping cannot expose a bundled UI.
        registry.addResourceHandler("/console/**")
            .addResourceLocations("classpath:/static/console/")
            .resourceChain(false)
            .addResolver(ConsoleResourceResolver(properties.enabled))
    }
}
