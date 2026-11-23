package com.charmeetchic.api.messaging.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Evento del exchange FANOUT {@code charmeetchic.ventas}: se creó una orden. Se publica UNA vez y RabbitMQ
 * entrega una copia a cada cola suscrita ({@code ventas.auditoria} y {@code ventas.metricas}).
 *
 * @param eventId id único (messageId AMQP): cada suscriptor lo usa para descartar duplicados
 * @param lines   cantidad de productos distintos en la orden
 * @param units   unidades totales vendidas
 */
public record SaleCompletedEvent(
        @NotBlank(message = "eventId es obligatorio") String eventId,
        @NotNull(message = "orderId es obligatorio") Long orderId,
        @NotBlank(message = "userId es obligatorio") String userId,
        @Positive(message = "lines debe ser mayor que 0") int lines,
        @Positive(message = "units debe ser mayor que 0") int units,
        @NotNull(message = "total es obligatorio") @PositiveOrZero BigDecimal total,
        @NotNull(message = "occurredAt es obligatorio") Instant occurredAt) {
}
