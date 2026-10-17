package com.charmeetchic.api.messaging.event;

import java.time.Instant;

/**
 * Respuesta del consumidor RPC de stock.
 *
 * @param found       false si no existe un producto activo con ese SKU
 * @param lowStock    true si el stock está bajo el umbral de app.inventory.low-stock-threshold
 * @param respondedBy hilo del consumidor que respondió (muestra en la demo que contestó otro componente)
 */
public record StockQueryReply(String sku, boolean found, Long productId, String productName, Integer stock,
                              boolean lowStock, Integer threshold, Instant respondedAt, String respondedBy) {

    public static StockQueryReply notFound(String sku, String respondedBy) {
        return new StockQueryReply(sku, false, null, null, null, false, null, Instant.now(), respondedBy);
    }
}
