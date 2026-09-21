package com.charmeetchic.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Cuerpo de {@code POST /orders}: carrito + dirección de envío. El usuario NO se envía:
 * se toma del token JWT.
 */
public record OrderRequestDTO(
        @NotBlank(message = "La dirección de envío es obligatoria")
        @Size(max = 500, message = "La dirección no puede superar 500 caracteres")
        String shippingAddress,

        @NotEmpty(message = "La orden debe tener al menos un producto")
        @Size(max = 50, message = "Una orden admite hasta 50 líneas")
        List<@Valid CartItemDTO> items) {
}
