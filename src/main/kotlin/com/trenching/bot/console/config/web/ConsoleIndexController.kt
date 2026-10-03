package com.trenching.bot.console.config.web

import com.trenching.bot.console.config.ConditionalOnConsoleEnabled
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
@ConditionalOnConsoleEnabled
class ConsoleIndexController {
    @GetMapping("/console", "/console/")
    fun index(): ResponseEntity<Resource> {
        val index = ClassPathResource("static/console/index.html")
        return if (index.exists()) ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(index)
        else ResponseEntity.notFound().build()
    }
}
