package com.charmeetchic.api.streaming.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Evento del tópico {@code ventas.eventos} (key = {@code orderId}): una orden se creó o se canceló.
 *
 * <p>Se valida dos veces: el productor no publica un evento inválido y el consumidor rechaza (al .DLT, sin
 * reintentos) el que llegue inválido de cualquier otro productor.
 *
 * @param eventId   id único del evento (UUID). Kafka entrega "al menos una vez": tras un reintento o un
 *                  rebalanceo el mismo evento puede llegar de nuevo, y los consumidores usan este id para no
 *                  contarlo dos veces
 * @param tipo      qué pasó (CREADA o CANCELADA); una cancelación RESTA de las métricas
 * @param timestamp cuándo ocurrió en el negocio (no cuándo se consumió): "ventas del último minuto" se calcula
 *                  con este valor, así un consumidor atrasado no cuenta como recientes ventas viejas
 * @param orderId   id de la orden; es la KEY del mensaje (todos los eventos de una orden van a la misma
 *                  partición y se procesan en orden). Es texto para que las órdenes sintéticas de la demo
 *                  ({@code SIM-...}) no choquen con ids reales
 * @param userId    quién compró (trazabilidad en el historial)
 * @param items     líneas de la orden: de aquí sale el top de productos
 * @param total     total con impuestos: de aquí salen los ingresos
 * @param origen    REAL (orden de un cliente) o SIMULACION (POST /streaming/simulate); permite filtrar la demo
 */
public record VentaEvent(
        @NotBlank(message = "eventId es obligatorio") String eventId,
        @NotNull(message = "tipo es obligatorio") VentaTipo tipo,
        @NotNull(message = "timestamp es obligatorio") Instant timestamp,
        @NotBlank(message = "orderId es obligatorio") @Size(max = 64) String orderId,
        @NotBlank(message = "userId es obligatorio") String userId,
        @NotEmpty(message = "la venta debe tener al menos un ítem") List<@Valid @NotNull Item> items,
        @NotNull(message = "total es obligatorio") @PositiveOrZero(message = "total no puede ser negativo")
        BigDecimal total,
        @NotNull(message = "origen es obligatorio") Origen origen) {

    public VentaEvent {
        items = items == null ? null : List.copyOf(items);
    }

    /** Qué le pasó a la orden. */
    public enum VentaTipo { CREADA, CANCELADA }

    /** De dónde viene el evento. */
    public enum Origen { REAL, SIMULACION }

    /**
     * Una línea de la orden.
     *
     * @param sku            identifica el producto en el ranking (es estable; el nombre puede cambiar)
     * @param nombre         para mostrarlo en el dashboard sin consultar la BD
     * @param cantidad       unidades de la línea
     * @param precioUnitario precio congelado al momento de la venta
     */
    public record Item(
            @NotBlank(message = "sku es obligatorio") String sku,
            @NotBlank(message = "nombre es obligatorio") String nombre,
            @Positive(message = "cantidad debe ser mayor que 0") int cantidad,
            @NotNull(message = "precioUnitario es obligatorio") @PositiveOrZero BigDecimal precioUnitario) {
    }

    /** Unidades totales de la orden. */
    @JsonIgnore
    public int unidades() {
        return items == null ? 0 : items.stream().mapToInt(Item::cantidad).sum();
    }
}
