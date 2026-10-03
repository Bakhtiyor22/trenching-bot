package com.trenching.bot.console.config.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.Resource
import org.springframework.web.servlet.resource.AbstractResourceResolver
import org.springframework.web.servlet.resource.ResourceResolverChain

class ConsoleResourceResolver(private val enabled: Boolean) : AbstractResourceResolver() {
    override fun resolveResourceInternal(
        request: HttpServletRequest?,
        requestPath: String,
        locations: List<Resource>,
        chain: ResourceResolverChain,
    ): Resource? {
        if (!enabled) return null
        val resource = chain.resolveResource(request, requestPath, locations)
        if (resource != null) return resource
        if (requestPath.startsWith("assets/") || requestPath.contains('.') || requestPath.contains('\\')) return null
        return chain.resolveResource(request, "index.html", locations)
    }

    override fun resolveUrlPathInternal(
        resourceUrlPath: String,
        locations: List<Resource>,
        chain: ResourceResolverChain,
    ): String? = if (enabled) chain.resolveUrlPath(resourceUrlPath, locations) else null
}
