package com.charmeetchic.api.dto;

import com.charmeetchic.api.entity.Category;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Representación REST de una {@link Category}. {@code id} y {@code createdAt} son de solo lectura.
 */
public record CategoryDTO(
        Long id,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
        String name,

        @Size(max = 500, message = "La descripción no puede superar 500 caracteres")
        String description,

        LocalDateTime createdAt) {

    public static CategoryDTO from(Category c) {
        return new CategoryDTO(c.getId(), c.getName(), c.getDescription(), c.getCreatedAt());
    }
}
