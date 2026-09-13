package com.tufin.debate.shared.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.util.concurrent.TimeUnit

/**
 * Single-container hosting: when app.static-dir points at the built Angular app, the backend
 * serves it with an SPA fallback (client routes like /rooms/x resolve to index.html). API,
 * WebSocket, and actuator paths are never swallowed by the fallback. Empty in local dev, where
 * the Angular dev server runs separately.
 */
@Configuration
class SpaConfig(
    @param:Value("\${app.static-dir:}") private val staticDir: String,
) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        if (staticDir.isBlank()) return
        val location = "file:${staticDir.trimEnd('/', '\\')}/"
        registry.addResourceHandler("/**")
            .addResourceLocations(location)
            .setCachePeriod(TimeUnit.HOURS.toSeconds(1).toInt())
            .resourceChain(true)
            .addResolver(object : PathResourceResolver() {
                override fun getResource(resourcePath: String, location: Resource): Resource? {
                    if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws") ||
                        resourcePath.startsWith("actuator") || resourcePath.startsWith("v3/")
                    ) {
                        return null
                    }
                    val requested = location.createRelative(resourcePath)
                    return if (requested.exists() && requested.isReadable) requested
                    else location.createRelative("index.html")
                }
            })
    }
}
