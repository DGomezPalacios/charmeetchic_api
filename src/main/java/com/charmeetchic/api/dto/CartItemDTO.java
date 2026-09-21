package com.charmeetchic.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Línea del carrito que envía el cliente al crear una orden. Solo se recibe producto y cantidad:
 * el precio SIEMPRE lo determina el servidor.
 */
public record CartItemDTO(
        @NotNull(message = "El producto es obligatorio")
        Long productId,

        @NotNull(message = "La cantidad es obligatoria")
        @Min(value = 1, message = "La cantidad mínima es 1")
        @Max(value = 100, message = "La cantidad máxima por producto es 100")
        Integer quantity) {
}
