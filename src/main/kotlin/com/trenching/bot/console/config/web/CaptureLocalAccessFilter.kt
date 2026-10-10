package com.trenching.bot.console.config.web

import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

@Component
@Profile("local & !prod")
@ConditionalOnConsoleEnabled
class CaptureLocalAccessFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.requestURI != "/api/captures" && !request.requestURI.startsWith("/api/captures/")
    override fun doFilterInternal(request: HttpServletRequest,response: HttpServletResponse,chain: FilterChain) {
        val allowedPeers=setOf("127.0.0.1","::1","0:0:0:0:0:0:0:1")
        // A same-origin GET carries no Origin header, so the Host also has to be local: the public preview
        // tunnel reaches the backend from a loopback peer but keeps its own Host.
        val localHost=request.serverName in setOf("localhost","127.0.0.1","[::1]","::1")
        val origin=request.getHeader("Origin")
        val uri=origin?.let { runCatching { URI(it) }.getOrNull() }
        val hostAllowed=uri!=null && uri.host in setOf("localhost","127.0.0.1","[::1]") && uri.scheme in setOf("http","https") && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.path.isNullOrEmpty()
        val portAllowed=uri!=null && (uri.port==5173 || (uri.port==request.serverPort && uri.scheme==request.scheme))
        if(request.remoteAddr !in allowedPeers || !localHost || (origin!=null && (!hostAllowed || !portAllowed)) || request.getHeader("Sec-Fetch-Site")=="cross-site") {
            response.status=403; response.contentType="application/json"; response.writer.write("{\"code\":\"LOCAL_ACCESS_REQUIRED\",\"message\":\"Capture access requires a local browser origin and peer\",\"fields\":{}}")
            return
        }
        chain.doFilter(request,response)
    }
}
