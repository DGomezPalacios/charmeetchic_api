package com.charmeetchic.api.enums;

/**
 * Ciclo de vida de una orden. El orden de declaración es significativo:
 * una orden solo puede avanzar hacia estados posteriores (ver
 * {@code OrderService#updateOrderStatus}).
 */
public enum OrderStatus {
    /** Recién creada, stock ya reservado. Es el único estado en el que el cliente puede cancelarla. */
    PENDING,
    /** La tienda está preparando el pedido. */
    PROCESSING,
    /** Despachada al cliente. */
    SENT,
    /** Entregada. Estado final. */
    DELIVERED
}
