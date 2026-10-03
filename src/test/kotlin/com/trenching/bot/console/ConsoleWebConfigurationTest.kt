package com.trenching.bot.console

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.core.env.MapPropertySource
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleWebConfigurationTest {
    @Test
    fun `enabled console serves index and extensionless client routes`() {
        withContext(true) { mvc ->
            listOf("/console", "/console/", "/console/datasets/sample", "/console/runs/sample").forEach { path ->
                val result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                    .andReturn().response
                assertEquals(200, result.status, path)
                assertTrue(result.contentAsString.contains("console-test-index"), path)
            }
        }
    }

    @Test
    fun `enabled console serves assets but missing assets never return the index`() {
        withContext(true) { mvc ->
            val asset = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/console/assets/console-test.js"))
                .andReturn().response
            assertEquals(200, asset.status)
            assertTrue(asset.contentAsString.contains("consoleTest"))
            listOf("/console/assets/missing.js", "/console/missing.css", "/console/assets/missing").forEach { path ->
                assertEquals(404, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                    .andReturn().response.status, path)
            }
        }
    }

    @Test
    fun `disabled console blocks bundled resources even with default static mapping`() {
        withContext(false) { mvc ->
            listOf("/console", "/console/", "/console/index.html", "/console/assets/console-test.js", "/console/runs/sample").forEach { path ->
                assertEquals(404, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                    .andReturn().response.status, path)
            }
        }
    }

    private fun withContext(enabled: Boolean, test: (org.springframework.test.web.servlet.MockMvc) -> Unit) {
        AnnotationConfigWebApplicationContext().use { context ->
            context.servletContext = MockServletContext()
            context.environment.propertySources.addFirst(MapPropertySource("console-test", mapOf("trenching.console.enabled" to enabled)))
            context.register(DefaultStaticConfiguration::class.java)
            context.scan("com.trenching.bot.console.config.web")
            context.refresh()
            test(MockMvcBuilders.webAppContextSetup(context).build())
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    class DefaultStaticConfiguration : WebMvcConfigurer {
        override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
            registry.addResourceHandler("/**").addResourceLocations("classpath:/static/")
        }
    }
}
