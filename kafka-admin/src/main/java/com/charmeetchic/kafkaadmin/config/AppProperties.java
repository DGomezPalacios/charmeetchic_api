package com.charmeetchic.kafkaadmin.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
        @NotNull @Valid Kafka kafka,
        @NotNull @Valid Admin admin,
        @NotNull @Valid Security security) {

    /**
     * @param bootstrapServers brokers "semilla" del clúster
     * @param timeoutMs        espera máxima de cada operación del AdminClient
     */
    public record Kafka(@NotBlank(message = "falta app.kafka.bootstrap-servers") String bootstrapServers,
                        @Min(1000) long timeoutMs) {
    }

    /**
     * @param protectedTopics      tópicos de la API de inventario: no se pueden borrar
     * @param maxPartitions        máximo de particiones que se puede pedir
     * @param maxReplicationFactor máximo factor de replicación (= brokers del clúster)
     */
    public record Admin(List<@NotBlank String> protectedTopics,
                        @Min(1) int maxPartitions,
                        @Min(1) @Max(10) short maxReplicationFactor) {

        public Admin {
            protectedTopics = protectedTopics == null ? List.of() : List.copyOf(protectedTopics);
        }
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
