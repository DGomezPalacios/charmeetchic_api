package com.charmeetchic.api.dto;

import com.charmeetchic.api.entity.Product;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Representación REST de un {@link Product}, usada tanto para respuestas como para el cuerpo de
 * POST/PUT.
 *
 * <p>Campos de solo lectura (se ignoran al recibirlos): {@code id}, {@code categoryName},
 * {@code createdAt}, {@code updatedAt}. El campo {@code version} es opcional en un PUT: si se envía,
 * debe coincidir con la versión actual o la operación responde 409 (control de concurrencia
 * optimista: "estoy editando la versión que vi").
 */
public record ProductDTO(
        Long id,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 150, message = "El nombre no puede superar 150 caracteres")
        String name,

        @Size(max = 1000, message = "La descripción no puede superar 1000 caracteres")
        String description,

        @NotNull(message = "El precio es obligatorio")
        @DecimalMin(value = "0.0", inclusive = false, message = "El precio debe ser mayor que 0")
        @Digits(integer = 10, fraction = 2, message = "El precio admite hasta 10 enteros y 2 decimales")
        BigDecimal price,

        @NotNull(message = "El costo es obligatorio")
        @DecimalMin(value = "0.0", message = "El costo no puede ser negativo")
        @Digits(integer = 10, fraction = 2, message = "El costo admite hasta 10 enteros y 2 decimales")
        BigDecimal cost,

        @NotBlank(message = "El SKU es obligatorio")
        @Size(max = 50, message = "El SKU no puede superar 50 caracteres")
        String sku,

        @NotNull(message = "El stock es obligatorio")
        @Min(value = 0, message = "El stock no puede ser negativo")
        @Max(value = 1_000_000, message = "El stock no puede superar 1.000.000")
        Integer stock,

        @NotNull(message = "La categoría es obligatoria")
        Long categoryId,

        String categoryName,

        List<@NotBlank(message = "Las imágenes no pueden ser vacías")
             @Size(max = 500, message = "Cada imagen admite hasta 500 caracteres") String> images,

        /** Si es null: en la creación se asume {@code true}; en la actualización se conserva el valor actual. */
        Boolean active,

        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version) {

    /** Convierte la entidad a DTO. Debe invocarse dentro de una transacción (categoría e imágenes son lazy). */
    public static ProductDTO from(Product p) {
        return new ProductDTO(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getPrice(),
                p.getCost(),
                p.getSku(),
                p.getStock(),
                p.getCategory().getId(),
                p.getCategory().getName(),
                List.copyOf(p.getImages()),
                p.isActive(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getVersion());
    }
}
