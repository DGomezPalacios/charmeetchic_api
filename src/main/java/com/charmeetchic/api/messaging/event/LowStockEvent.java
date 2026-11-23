package com.charmeetchic.api.messaging.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * Mensaje de la cola {@code inventario.stock-bajo}: un producto acaba de quedar bajo el stock mínimo.
 * Se publica una sola vez, cuando el stock CRUZA el umbral (antes >= umbral y después < umbral).
 *
 * @param threshold umbral configurado en {@code app.inventory.low-stock-threshold} al momento del evento
 */
public record LowStockEvent(
        @NotBlank(message = "eventId es obligatorio") String eventId,
        @NotNull(message = "productId es obligatorio") Long productId,
        @NotBlank(message = "sku es obligatorio") String sku,
        String productName,
        @NotNull(message = "stock es obligatorio") @PositiveOrZero Integer stock,
        @NotNull(message = "threshold es obligatorio") @Positive Integer threshold,
        @NotNull(message = "occurredAt es obligatorio") Instant occurredAt) {
}
