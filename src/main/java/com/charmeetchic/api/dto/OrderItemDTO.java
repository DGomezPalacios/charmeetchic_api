package com.charmeetchic.api.dto;

import com.charmeetchic.api.entity.OrderItem;

import java.math.BigDecimal;

/** Línea de una orden en las respuestas. {@code price} es el precio unitario congelado al comprar. */
public record OrderItemDTO(
        Long id,
        Long productId,
        String productName,
        String sku,
        Integer quantity,
        BigDecimal price,
        BigDecimal subtotal) {

    public static OrderItemDTO from(OrderItem item) {
        return new OrderItemDTO(
                item.getId(),
                item.getProduct().getId(),
                item.getProduct().getName(),
                item.getProduct().getSku(),
                item.getQuantity(),
                item.getPrice(),
                item.getSubtotal());
    }
}
