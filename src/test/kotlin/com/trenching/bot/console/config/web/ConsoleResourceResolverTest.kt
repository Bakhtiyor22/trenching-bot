package com.trenching.bot.console.config.web

import jakarta.servlet.http.HttpServletRequest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.resource.ResourceResolverChain

/** Which request paths the console page answers (variation-versions plan Domain Rule 11): a path is a file only when its last segment ends in a letter-led extension. */
class ConsoleResourceResolverTest {
    private val index = ByteArrayResource(ByteArray(0), "index.html")
    private val chain = object : ResourceResolverChain {
        override fun resolveResource(request: HttpServletRequest?, requestPath: String, locations: List<Resource>): Resource? =
            index.takeIf { requestPath == INDEX }

        override fun resolveUrlPath(resourcePath: String, locations: List<Resource>): String? = null
    }

    @ParameterizedTest
    @ValueSource(strings = ["automation/variations/V03.1", "automation/variations/V03.12", "runs", "automation/days/2026-11-02", "automation/variations/V03"])
    fun `a page route answers the console page, a dotted number in it included`(path: String) {
        assertEquals(index, resolve(path))
    }

    @ParameterizedTest
    @ValueSource(strings = ["favicon.ico", "assets/x.js", "assets/missing", "main.css", "fonts/inter.woff2", "app.js.map", "x.v2", "dir\\file"])
    fun `a missing file or asset answers nothing`(path: String) {
        assertNull(resolve(path))
    }

    @ParameterizedTest
    @ValueSource(strings = ["automation/variations/V03.1", "runs"])
    fun `a disabled console answers nothing`(path: String) {
        assertNull(ConsoleResourceResolver(false).resolveResource(null, path, emptyList(), chain))
    }

    private fun resolve(path: String): Resource? = ConsoleResourceResolver(true).resolveResource(null, path, emptyList(), chain)

    private companion object {
        const val INDEX = "index.html"
    }
}
