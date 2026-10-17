package com.charmeetchic.api.messaging.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de mensajería leída desde {@code app.messaging} en application.yml: nombres de exchanges,
 * colas y routing keys, política de reintentos, RPC, retención y opciones de demo.
 *
 * <p>Por qué existe esta clase (y no {@code @Value} sueltos por todo el código):
 * <ul>
 *   <li><b>Un solo lugar</b>: los nombres viven en el yml y aquí se tipan. Productores, consumidores y
 *       {@link RabbitMQConfig} reciben este objeto; nadie escribe "inventario.stock-bajo" a mano.</li>
 *   <li><b>Falla rápido</b>: {@code @Validated} aplica Bean Validation al arrancar. Si alguien deja un
 *       nombre vacío, pone la DLQ con el mismo nombre que la cola o configura 0 reintentos, la aplicación
 *       NO levanta y el log dice exactamente qué propiedad está mal.</li>
 * </ul>
 *
 * @param enabled interruptor general: con {@code false} no se publica nada ni se conecta al arrancar (tests)
 */
@Validated
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
        boolean enabled,
        @NotNull(message = "falta app.messaging.retry") @Valid Retry retry,
        @NotNull(message = "falta app.messaging.rpc") @Valid Rpc rpc,
        @NotNull(message = "falta app.messaging.demo") Demo demo,
        @NotNull(message = "falta app.messaging.retention") @Valid Retention retention,
        @NotNull(message = "falta app.messaging.exchanges") @Valid Exchanges exchanges,
        @NotNull(message = "falta app.messaging.queues") @Valid Queues queues) {

    /** Límite de RabbitMQ para nombres de colas y exchanges (255 bytes). */
    private static final int MAX_NAME = 255;

    /**
     * Política de reintentos de los consumidores ante errores TRANSITORIOS.
     *
     * @param maxAttempts intentos totales (incluye el primero); al agotarlos el mensaje va a la DLQ
     * @param backoffMs   espera base antes de reintentar; se multiplica por el número de intento
     */
    public record Retry(
            @Min(value = 1, message = "max-attempts debe ser al menos 1")
            @Max(value = 10, message = "max-attempts no puede superar 10") int maxAttempts,
            @Min(value = 0, message = "backoff-ms no puede ser negativo")
            @Max(value = 30_000, message = "backoff-ms no puede superar 30000 (30 s)") long backoffMs) {
    }

    /** @param timeoutMs espera máxima de la respuesta síncrona (y TTL de la cola de consultas) */
    public record Rpc(
            @Min(value = 100, message = "rpc.timeout-ms debe ser al menos 100")
            @Max(value = 30_000, message = "rpc.timeout-ms no puede superar 30000 (30 s)") long timeoutMs) {
    }

    /** Opciones para la demo: permitir fallos simulados a pedido. */
    public record Demo(boolean simulatedFailuresEnabled) {
    }

    /**
     * Retención común.
     *
     * @param overflow            qué hace una cola principal llena (drop-head = descarta el más antiguo)
     * @param deadLetterTtlMs     cuánto se guarda un mensaje en una DLQ
     * @param deadLetterMaxLength máximo de mensajes en cada DLQ
     */
    public record Retention(
            @NotBlank
            @Pattern(regexp = "drop-head|reject-publish",
                    message = "retention.overflow debe ser drop-head o reject-publish")
            String overflow,
            @Positive(message = "retention.dead-letter-ttl-ms debe ser mayor que 0") long deadLetterTtlMs,
            @Positive(message = "retention.dead-letter-max-length debe ser mayor que 0") int deadLetterMaxLength) {
    }

    /** Los exchanges de la aplicación. */
    public record Exchanges(
            @NotBlank(message = "el nombre del exchange de inventario (direct) no puede estar vacío")
            @Size(max = MAX_NAME) String inventario,
            @NotBlank(message = "el nombre del exchange de notificaciones (topic) no puede estar vacío")
            @Size(max = MAX_NAME) String notificaciones,
            @NotBlank(message = "el nombre del exchange de ventas (fanout) no puede estar vacío")
            @Size(max = MAX_NAME) String ventas,
            @NotBlank(message = "el nombre del Dead Letter Exchange no puede estar vacío")
            @Size(max = MAX_NAME) String deadLetter) {
    }

    /** Una propiedad por caso de uso. */
    public record Queues(
            @NotNull(message = "falta la configuración de la cola stock-bajo") @Valid QueueProperties stockBajo,
            @NotNull(message = "falta la configuración de la cola movimientos") @Valid QueueProperties movimientos,
            @NotNull(message = "falta la configuración de la cola reportes") @Valid QueueProperties reportes,
            @NotNull(message = "falta la configuración de la cola consulta-stock") @Valid RpcQueueProperties consultaStock,
            @NotNull(message = "falta la configuración de la cola ventas-auditoria") @Valid SubscriberQueueProperties ventasAuditoria,
            @NotNull(message = "falta la configuración de la cola ventas-metricas") @Valid SubscriberQueueProperties ventasMetricas) {
    }

    /**
     * Cola de trabajo con routing key y su DLQ.
     *
     * @param name              nombre de la cola principal
     * @param routingKey        binding key con que la cola se une a su exchange (en topic es un patrón)
     * @param publishRoutingKey routing key concreta para publicar (opcional; si falta se usa routingKey)
     * @param deadLetterQueue   nombre de la cola donde terminan los mensajes que no se pudieron procesar
     * @param maxLength         máximo de mensajes en la cola (retención)
     * @param messageTtlMs      vida máxima de cada mensaje; null = no vence
     */
    public record QueueProperties(
            @NotBlank(message = "el nombre de la cola no puede estar vacío")
            @Size(max = MAX_NAME) String name,
            @NotBlank(message = "la routing key no puede estar vacía")
            @Size(max = MAX_NAME) String routingKey,
            @Size(max = MAX_NAME) String publishRoutingKey,
            @NotBlank(message = "el nombre de la DLQ no puede estar vacío")
            @Size(max = MAX_NAME) String deadLetterQueue,
            @NotNull(message = "falta max-length (retención)") @Positive(message = "max-length debe ser mayor que 0")
            Integer maxLength,
            @Positive(message = "message-ttl-ms debe ser mayor que 0") Long messageTtlMs) {

        /** Routing key que usa el productor al publicar en esta cola. */
        public String publishKey() {
            return publishRoutingKey == null || publishRoutingKey.isBlank() ? routingKey : publishRoutingKey;
        }

        /** Una DLQ con el mismo nombre que la cola haría que el mensaje fallido vuelva a la misma cola. */
        @AssertTrue(message = "la DLQ debe tener un nombre distinto al de la cola principal")
        public boolean isDeadLetterQueueDistinct() {
            return name == null || deadLetterQueue == null || !name.equals(deadLetterQueue);
        }

        /** Se publica con una key concreta: los comodines (* o #) solo tienen sentido en el binding. */
        @AssertTrue(message = "la routing key de publicación no puede contener comodines (* o #)")
        public boolean isPublishKeyConcrete() {
            String key = publishKey();
            return key == null || !(key.contains("*") || key.contains("#"));
        }
    }

    /** Cola de peticiones RPC (sin DLQ; su TTL es {@code rpc.timeout-ms}). */
    public record RpcQueueProperties(
            @NotBlank(message = "el nombre de la cola RPC no puede estar vacío") @Size(max = MAX_NAME) String name,
            @NotBlank(message = "la routing key de la cola RPC no puede estar vacía") @Size(max = MAX_NAME) String routingKey,
            @NotNull(message = "falta max-length (retención)") @Positive(message = "max-length debe ser mayor que 0")
            Integer maxLength) {
    }

    /** Cola suscrita a un exchange fanout: no lleva routing key (el fanout la ignora). */
    public record SubscriberQueueProperties(
            @NotBlank(message = "el nombre de la cola no puede estar vacío") @Size(max = MAX_NAME) String name,
            @NotBlank(message = "el nombre de la DLQ no puede estar vacío") @Size(max = MAX_NAME) String deadLetterQueue,
            @NotNull(message = "falta max-length (retención)") @Positive(message = "max-length debe ser mayor que 0")
            Integer maxLength,
            @Positive(message = "message-ttl-ms debe ser mayor que 0") Long messageTtlMs) {

        @AssertTrue(message = "la DLQ debe tener un nombre distinto al de la cola principal")
        public boolean isDeadLetterQueueDistinct() {
            return name == null || deadLetterQueue == null || !name.equals(deadLetterQueue);
        }
    }
}
