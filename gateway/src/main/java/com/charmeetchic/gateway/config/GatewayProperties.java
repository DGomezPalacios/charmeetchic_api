package com.charmeetchic.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** Configuración propia del gateway ({@code app.*}), validada al arrancar. */
@Validated
@ConfigurationProperties(prefix = "app")
public record GatewayProperties(@NotNull @Valid Security security, @NotNull @Valid Cors cors) {

    /**
     * @param firebaseProjectId Project ID de Firebase (issuer y audience exigidos)
     * @param jwkSetUri         claves públicas de Google
     * @param publicPaths       rutas que no exigen token (patrones de Spring, p. ej. /api/health)
     */
    public record Security(@NotBlank(message = "falta app.security.firebase-project-id") String firebaseProjectId,
                           @NotBlank(message = "falta app.security.jwk-set-uri") String jwkSetUri,
                           @NotNull List<@NotBlank String> publicPaths) {
    }

    /** @param allowedOrigins orígenes exactos del frontend, p. ej. https://charme-et-chic.web.app */
    public record Cors(@NotEmpty(message = "app.cors.allowed-origins no puede quedar vacío")
                       List<@NotBlank String> allowedOrigins) {
    }
}
