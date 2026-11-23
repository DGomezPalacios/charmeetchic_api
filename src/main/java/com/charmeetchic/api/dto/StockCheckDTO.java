package com.charmeetchic.api.dto;

import java.time.Instant;

/**
 * Respuesta de GET /api/inventory/stock-check/{sku} (consulta síncrona por RabbitMQ).
 *
 * @param respondedBy hilo del consumidor RPC que contestó
 * @param roundTripMs lo que tardó la ida y vuelta por RabbitMQ (la petición HTTP esperó este tiempo)
 */
public record StockCheckDTO(String sku, Long productId, String productName, Integer stock, boolean lowStock,
                            Integer threshold, String respondedBy, Instant respondedAt, long roundTripMs) {
}
