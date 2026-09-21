package com.charmeetchic.api.dto;

import com.charmeetchic.api.entity.Order;
import com.charmeetchic.api.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Representación (solo lectura) de una orden con sus líneas e importes. */
public record OrderDTO(
        Long id,
        String userId,
        List<OrderItemDTO> items,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal total,
        OrderStatus status,
        String shippingAddress,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** Debe invocarse dentro de una transacción (las líneas y sus productos son lazy). */
    public static OrderDTO from(Order o) {
        return new OrderDTO(
                o.getId(),
                o.getUserId(),
                o.getItems().stream().map(OrderItemDTO::from).toList(),
                o.getSubtotal(),
                o.getTax(),
                o.getTotal(),
                o.getStatus(),
                o.getShippingAddress(),
                o.getCreatedAt(),
                o.getUpdatedAt());
    }
}
