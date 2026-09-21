package com.charmeetchic.api.dto;

import com.charmeetchic.api.enums.OrderStatus;
import jakarta.validation.constraints.NotNull;

/** Cuerpo de {@code PUT /orders/{id}/status}: {@code {"status": "PROCESSING"}}. */
public record OrderStatusDTO(
        @NotNull(message = "El estado es obligatorio")
        OrderStatus status) {
}
