package com.charmeetchic.api.messaging.event;

import com.charmeetchic.api.enums.MovementType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * Mensaje de la cola {@code inventario.movimientos}: una entrada o salida de stock que se debe auditar.
 *
 * @param eventId       identificador único del evento (también es el messageId de AMQP); permite descartar
 *                      duplicados si el mismo mensaje llega dos veces
 * @param quantity      variación con signo: negativa = salida, positiva = entrada
 * @param previousStock stock antes del movimiento
 * @param newStock      stock después del movimiento
 * @param reference     de dónde vino el movimiento (p. ej. "orden #12")
 */
public record StockMovementEvent(
        @NotBlank(message = "eventId es obligatorio") String eventId,
        @NotNull(message = "productId es obligatorio") Long productId,
        @NotBlank(message = "sku es obligatorio") String sku,
        String productName,
        @NotNull(message = "type es obligatorio") MovementType type,
        @NotNull(message = "quantity es obligatorio") Integer quantity,
        @NotNull(message = "previousStock es obligatorio") @PositiveOrZero Integer previousStock,
        @NotNull(message = "newStock es obligatorio") @PositiveOrZero Integer newStock,
        String reference,
        @NotNull(message = "occurredAt es obligatorio") Instant occurredAt) {

    /** El movimiento debe cuadrar: stock anterior + variación = stock nuevo, y la variación no puede ser 0. */
    @AssertTrue(message = "el movimiento no cuadra: previousStock + quantity debe ser igual a newStock (y quantity != 0)")
    @JsonIgnore // es una regla de validación, no un dato: no debe viajar en el JSON
    public boolean isConsistent() {
        if (quantity == null || previousStock == null || newStock == null) {
            return true; // ya lo reportan los @NotNull
        }
        return quantity != 0 && previousStock + quantity == newStock;
    }
}
