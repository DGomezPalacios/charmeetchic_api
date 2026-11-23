package com.charmeetchic.rabbitadmin.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /queues}.
 *
 * <pre>{"name": "pedidos.web", "durable": true, "deadLetterExchange": "mi.dlx", "messageTtlMs": 60000}</pre>
 *
 * @param durable              sobrevive a un reinicio del broker (por defecto true)
 * @param autoDelete           se borra sola cuando se desconecta su último consumidor (por defecto false)
 * @param deadLetterExchange   exchange al que van los mensajes rechazados o vencidos (opcional, debe existir)
 * @param deadLetterRoutingKey routing key con que se reenvían al DLX (opcional; requiere deadLetterExchange)
 * @param messageTtlMs         tiempo de vida de cada mensaje en ms (opcional)
 * @param maxLength            máximo de mensajes en la cola (opcional)
 */
public record QueueRequest(
        @NotBlank(message = "el nombre de la cola es obligatorio")
        @Size(max = Names.MAX_LENGTH, message = "el nombre no puede superar 255 caracteres")
        @Pattern(regexp = Names.NAME_PATTERN, message = "el nombre " + Names.NAME_MESSAGE)
        @Pattern(regexp = Names.NOT_AMQ_PATTERN, message = Names.NOT_AMQ_MESSAGE)
        String name,
        Boolean durable,
        Boolean autoDelete,
        @Size(max = Names.MAX_LENGTH, message = "deadLetterExchange no puede superar 255 caracteres")
        @Pattern(regexp = Names.NAME_PATTERN, message = "deadLetterExchange " + Names.NAME_MESSAGE)
        String deadLetterExchange,
        @Size(max = Names.MAX_LENGTH, message = "deadLetterRoutingKey no puede superar 255 caracteres")
        @Pattern(regexp = Names.ROUTING_KEY_PATTERN, message = "deadLetterRoutingKey " + Names.ROUTING_KEY_MESSAGE)
        String deadLetterRoutingKey,
        @Positive(message = "messageTtlMs debe ser mayor que 0")
        @Max(value = 604_800_000L, message = "messageTtlMs no puede superar 7 días (604800000 ms)")
        Long messageTtlMs,
        @Positive(message = "maxLength debe ser mayor que 0")
        @Max(value = 1_000_000, message = "maxLength no puede superar 1000000")
        Integer maxLength) {

    /** Valores por defecto para los campos opcionales. */
    public QueueRequest {
        durable = durable == null || durable;
        autoDelete = autoDelete != null && autoDelete;
        deadLetterExchange = blankToNull(deadLetterExchange);
        deadLetterRoutingKey = blankToNull(deadLetterRoutingKey);
    }

    /** Una routing key de dead-letter sin exchange de dead-letter no tiene efecto: es una configuración inválida. */
    @AssertTrue(message = "deadLetterRoutingKey requiere deadLetterExchange")
    public boolean isDeadLetterConfigValid() {
        return deadLetterRoutingKey == null || deadLetterExchange != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
