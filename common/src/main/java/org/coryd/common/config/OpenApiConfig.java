package org.coryd.common.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every service scans org.coryd.common (see each *Application's scanBasePackages), so this
 * one class registers the bearer-JWT scheme everywhere at once - without it, springdoc has
 * no "Authorize" button at all, so Swagger UI has no way to attach a token to try-it-out
 * calls regardless of whether the token itself is valid.
 */
@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Paste the JWT access token returned by identity-service's /identity/auth/login"
)
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
