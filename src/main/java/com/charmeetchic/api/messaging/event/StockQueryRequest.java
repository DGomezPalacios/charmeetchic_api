package com.charmeetchic.api.messaging.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Petición SÍNCRONA (RPC) de la cola {@code inventario.consulta-stock}: "¿cuánto stock hay de este SKU?".
 * El que pregunta espera la respuesta ({@link StockQueryReply}) en la cola temporal de direct reply-to.
 */
public record StockQueryRequest(
        @NotBlank(message = "sku es obligatorio") @Size(max = 50, message = "sku no puede superar 50 caracteres")
        String sku) {
}
