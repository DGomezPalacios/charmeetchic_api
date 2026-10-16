package com.charmeetchic.api.messaging.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Nombres de exchanges, colas y routing keys, leídos desde {@code app.messaging} en application.yml.
 *
 * <p>Por qué existe esta clase (y no {@code @Value} sueltos por todo el código):
 * <ul>
 *   <li><b>Un solo lugar</b>: los nombres viven en el yml y aquí se tipan. Productores, consumidores y
 *       {@link RabbitMQConfig} reciben este objeto; nadie escribe "inventario.stock-bajo" a mano.</li>
 *   <li><b>Falla rápido</b>: {@code @Validated} aplica Bean Validation al arrancar. Si alguien deja un
 *       nombre vacío o pone la DLQ con el mismo nombre que la cola, la aplicación NO levanta y el log
 *       dice exactamente qué propiedad está mal.</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(
        @NotNull(message = "falta app.messaging.exchanges") @Valid Exchanges exchanges,
        @NotNull(message = "falta app.messaging.queues") @Valid Queues queues) {

    /** Límite de RabbitMQ para nombres de colas y exchanges (255 bytes). */
    private static final int MAX_NAME = 255;

    /** Los tres exchanges de la aplicación. */
    public record Exchanges(
            @NotBlank(message = "el nombre del exchange de inventario (direct) no puede estar vacío")
            @Size(max = MAX_NAME) String inventario,
            @NotBlank(message = "el nombre del exchange de notificaciones (topic) no puede estar vacío")
            @Size(max = MAX_NAME) String notificaciones,
            @NotBlank(message = "el nombre del Dead Letter Exchange no puede estar vacío")
            @Size(max = MAX_NAME) String deadLetter) {
    }

    /** Una propiedad por caso de uso. */
    public record Queues(
            @NotNull(message = "falta la configuración de la cola stock-bajo") @Valid QueueProperties stockBajo,
            @NotNull(message = "falta la configuración de la cola movimientos") @Valid QueueProperties movimientos,
            @NotNull(message = "falta la configuración de la cola reportes") @Valid QueueProperties reportes) {
    }

    /**
     * Configuración de una cola de trabajo y su DLQ.
     *
     * @param name            nombre de la cola principal
     * @param routingKey      binding key con que la cola se une a su exchange (en topic es un patrón)
     * @param deadLetterQueue nombre de la cola donde terminan los mensajes que no se pudieron procesar
     */
    public record QueueProperties(
            @NotBlank(message = "el nombre de la cola no puede estar vacío")
            @Size(max = MAX_NAME) String name,
            @NotBlank(message = "la routing key no puede estar vacía")
            @Size(max = MAX_NAME) String routingKey,
            @NotBlank(message = "el nombre de la DLQ no puede estar vacío")
            @Size(max = MAX_NAME) String deadLetterQueue) {

        /** Una DLQ con el mismo nombre que la cola haría que el mensaje fallido vuelva a la misma cola. */
        @AssertTrue(message = "la DLQ debe tener un nombre distinto al de la cola principal")
        public boolean isDeadLetterQueueDistinct() {
            return name == null || deadLetterQueue == null || !name.equals(deadLetterQueue);
        }
    }
}
