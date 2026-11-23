package com.charmeetchic.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Respuesta de {@code GET /api/streaming/metrics}. Contrato FIJO que consume el dashboard del frontend:
 * no renombrar ni quitar campos. Las fechas salen en ISO-8601 (Jackson de Spring Boot).
 *
 * @param generadoEn         momento en que se armó la respuesta
 * @param ventasUltimoMinuto órdenes creadas cuyo timestamp cae en el último minuto
 * @param ventasTotales      órdenes creadas menos canceladas desde que arrancó el consumidor
 * @param ingresosTotales    suma de totales de las creadas menos las canceladas
 * @param topProductos       hasta 5 productos con más unidades vendidas (netas)
 * @param stockCritico       productos con stock bajo el umbral (0 = quiebre), del menor al mayor
 * @param ultimosEventos     últimos eventos consumidos (el más reciente primero)
 */
public record StreamingMetricsDTO(
        Instant generadoEn,
        long ventasUltimoMinuto,
        long ventasTotales,
        BigDecimal ingresosTotales,
        List<TopProducto> topProductos,
        List<StockCritico> stockCritico,
        List<EventoReciente> ultimosEventos) {

    public record TopProducto(String sku, String nombre, long unidades) {
    }

    public record StockCritico(String sku, String nombre, int stock) {
    }

    /**
     * @param tipo      p. ej. VENTA_CREADA, VENTA_CANCELADA, STOCK_VENTA, DLT
     * @param detalle   descripción legible
     * @param timestamp cuándo ocurrió el evento
     * @param particion partición de la que se leyó
     * @param offset    posición del mensaje dentro de la partición
     */
    public record EventoReciente(String tipo, String detalle, Instant timestamp, int particion, long offset) {
    }
}
