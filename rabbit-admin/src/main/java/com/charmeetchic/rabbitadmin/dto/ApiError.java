package com.charmeetchic.rabbitadmin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Formato único de error (mismo que la API de inventario).
 *
 * @param details errores por campo cuando falla la validación (p. ej. {"name": "el nombre ... "})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String error, String message, String path,
                       Map<String, String> details) {
}
