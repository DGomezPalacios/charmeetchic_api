package com.charmeetchic.rabbitadmin.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Configuración propia ({@code app.*} en application.yml), validada al arrancar: si falta algo, la app no
 * levanta y el log dice qué propiedad corregir.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotNull @Valid Rabbitmq rabbitmq,
        @NotNull @Valid Admin admin,
        @NotNull @Valid Security security) {

    /** @param managementUrl URL base de la API HTTP del plugin de administración (sin /api) */
    public record Rabbitmq(@NotBlank(message = "falta app.rabbitmq.management-url") String managementUrl) {
    }

    /** @param protectedPrefixes prefijos de nombres que la API no deja crear ni borrar */
    public record Admin(@NotEmpty(message = "app.admin.protected-prefixes no puede quedar vacío")
                        List<@NotBlank String> protectedPrefixes) {
    }

    /**
     * @param firebaseProjectId Project ID de Firebase (issuer {@code https://securetoken.google.com/<id>},
     *                          audience {@code <id>})
     * @param jwkSetUri         claves públicas de Google para verificar la firma
     */
    public record Security(@NotBlank(message = "falta app.security.firebase-project-id") String firebaseProjectId,
                           @NotBlank(message = "falta app.security.jwk-set-uri") String jwkSetUri) {
    }
}
