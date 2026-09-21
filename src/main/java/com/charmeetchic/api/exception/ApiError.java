package com.charmeetchic.api.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Cuerpo JSON uniforme para TODAS las respuestas de error de la API.
 *
 * @param fieldErrors solo presente en errores de validación: campo -> mensaje
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {
}
