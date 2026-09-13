package com.tufin.debate.shared.config

import com.tufin.debate.identity.application.SecurityProperties
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(SecurityProperties::class)
class AppConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Trusted AI Negotiation Platform API")
                .description(
                    "Versioned REST API. Every endpoint is authorized server-side; " +
                        "see docs/trust-model.md for the platform invariants.",
                )
                .version("v1"),
        )
        .components(
            Components().addSecuritySchemes(
                "bearer",
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList("bearer"))
}
