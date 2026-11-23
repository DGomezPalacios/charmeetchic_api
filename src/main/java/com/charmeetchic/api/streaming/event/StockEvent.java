package com.charmeetchic.api.streaming.event;

import com.charmeetchic.api.enums.MovementType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * Evento del tópico COMPACTADO {@code inventario.stock} (key = {@code sku}): el stock de un producto cambió.
 * Como Kafka conserva el último mensaje de cada sku, el tópico completo equivale a la "tabla" de stock actual.
 *
 * @param eventId       id único (UUID) para trazabilidad en logs y en el .DLT
 * @param tipo          motivo del cambio (venta, cancelación, ajuste...); explica el evento en el dashboard
 * @param timestamp     cuándo ocurrió el cambio
 * @param sku           KEY del mensaje: la compactación conserva el último stock de cada sku
 * @param productId     id del producto en la BD (para enlazar con la API)
 * @param nombre        para mostrar el producto sin consultar la BD
 * @param stockAnterior stock antes del cambio (con stockActual permite ver la variación)
 * @param stockActual   stock después del cambio: el ÚNICO dato que el monitor necesita para decidir si hay
 *                      quiebre (0) o stock crítico
 * @param referencia    origen legible (p. ej. "orden #12"), igual que en la auditoría de RabbitMQ
 */
public record StockEvent(
        @NotBlank(message = "eventId es obligatorio") String eventId,
        @NotNull(message = "tipo es obligatorio") StockTipo tipo,
        @NotNull(message = "timestamp es obligatorio") Instant timestamp,
        @NotBlank(message = "sku es obligatorio") String sku,
        @NotNull(message = "productId es obligatorio") Long productId,
        @NotBlank(message = "nombre es obligatorio") String nombre,
        @NotNull(message = "stockAnterior es obligatorio") @PositiveOrZero Integer stockAnterior,
        @NotNull(message = "stockActual es obligatorio") @PositiveOrZero(message = "stockActual no puede ser negativo")
        Integer stockActual,
        String referencia) {

    /**
     * Motivo del cambio. SINCRONIZACION = foto del stock que la API publica al arrancar, para que el tópico
     * compactado refleje la BD aunque se hayan perdido eventos mientras Kafka no estaba disponible.
     */
    public enum StockTipo {
        INGRESO_INICIAL, VENTA, CANCELACION, AJUSTE, SINCRONIZACION;

        /** Traduce el motivo del movimiento de inventario (auditoría) al tipo del evento de streaming. */
        public static StockTipo from(MovementType type) {
            return switch (type) {
                case INGRESO_INICIAL -> INGRESO_INICIAL;
                case VENTA -> VENTA;
                case CANCELACION_ORDEN -> CANCELACION;
                case AJUSTE_MANUAL -> AJUSTE;
            };
        }
    }

    /** Una sincronización informa el stock tal cual; cualquier otro tipo debe traer un cambio real. */
    @AssertTrue(message = "un cambio de stock debe modificar el stock (stockAnterior != stockActual)")
    @JsonIgnore
    public boolean isCambioReal() {
        return tipo == null || tipo == StockTipo.SINCRONIZACION || stockAnterior == null || stockActual == null
                || !stockAnterior.equals(stockActual);
    }
}
