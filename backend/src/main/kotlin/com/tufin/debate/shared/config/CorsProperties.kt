package com.tufin.debate.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.cors")
data class CorsProperties(
    var allowedOrigins: List<String> = listOf("http://localhost:4200"),
)
