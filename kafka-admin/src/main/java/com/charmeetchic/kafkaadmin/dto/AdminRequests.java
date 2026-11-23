package com.charmeetchic.kafkaadmin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Cuerpos de las peticiones de escritura. Todos se validan con Bean Validation antes de llegar al servicio. */
public final class AdminRequests {

    /** Nombre de tópico válido en Kafka: letras, números, '.', '_' y '-', hasta 249 caracteres. */
    public static final String TOPIC_NAME = "^[a-zA-Z0-9._-]{1,249}$";

    private AdminRequests() {
    }

    /**
     * POST /topics.
     *
     * @param name              nombre del tópico (no puede empezar con "__": reservado para tópicos internos)
     * @param partitions        particiones (paralelismo máximo de un consumer group)
     * @param replicationFactor copias de cada partición (máximo = brokers del clúster)
     * @param configs           configs propias del tópico (lista blanca en {@link TopicConfigs})
     */
    public record CreateTopicRequest(
            @NotBlank(message = "el nombre del tópico es obligatorio")
            @Pattern(regexp = TOPIC_NAME, message = "el nombre solo admite letras, números, '.', '_' y '-' (máx. 249)")
            String name,
            @Min(value = 1, message = "partitions debe ser al menos 1")
            @Max(value = 50, message = "partitions no puede superar 50")
            int partitions,
            @Min(value = 1, message = "replicationFactor debe ser al menos 1")
            @Max(value = 3, message = "replicationFactor no puede superar 3 (hay 3 brokers)")
            short replicationFactor,
            @Size(max = 20) Map<String, String> configs) {

        @AssertTrue(message = "los nombres que empiezan con '__' están reservados para tópicos internos de Kafka")
        @JsonIgnore
        public boolean isNotInternalName() {
            return name == null || !name.startsWith("__");
        }

        @AssertTrue(message = "configs inválidas")
        @JsonIgnore
        public boolean isConfigsValid() {
            return TopicConfigs.errors(configs).isEmpty();
        }
    }

    /** PATCH /topics/{name}/configs: configs a cambiar (las que no se nombran quedan como están). */
    public record UpdateConfigsRequest(
            @NotEmpty(message = "indica al menos una config a cambiar") @Size(max = 20) Map<String, String> configs) {

        @AssertTrue(message = "configs inválidas")
        @JsonIgnore
        public boolean isConfigsValid() {
            return TopicConfigs.errors(configs).isEmpty();
        }
    }

    /** POST /topics/{name}/partitions: nuevo TOTAL de particiones (Kafka solo permite aumentar). */
    public record IncreasePartitionsRequest(
            @Min(value = 2, message = "totalPartitions debe ser al menos 2")
            @Max(value = 50, message = "totalPartitions no puede superar 50")
            int totalPartitions) {
    }

    /** A dónde mover los offsets de un grupo. */
    public enum ResetStrategy {
        /** Al mensaje más antiguo retenido: el grupo reprocesa todo. */
        EARLIEST,
        /** Al final del log: el grupo salta todo lo pendiente (lag 0). */
        LATEST,
        /** A un offset exacto (campo {@code offset}). */
        OFFSET
    }

    /**
     * POST /consumer-groups/{groupId}/reset-offsets.
     *
     * @param topic     tópico cuyos offsets se mueven
     * @param strategy  EARLIEST, LATEST u OFFSET
     * @param offset    offset destino (solo con OFFSET)
     * @param partition partición concreta; null = todas las del tópico
     */
    public record ResetOffsetsRequest(
            @NotBlank(message = "topic es obligatorio") @Pattern(regexp = TOPIC_NAME, message = "nombre de tópico inválido")
            String topic,
            @NotNull(message = "strategy es obligatoria (EARLIEST, LATEST u OFFSET)") ResetStrategy strategy,
            @PositiveOrZero(message = "offset no puede ser negativo") Long offset,
            @PositiveOrZero(message = "partition no puede ser negativa") Integer partition) {

        @AssertTrue(message = "con strategy=OFFSET hay que indicar 'offset'")
        @JsonIgnore
        public boolean isOffsetPresentWhenNeeded() {
            return strategy != ResetStrategy.OFFSET || offset != null;
        }
    }
}
