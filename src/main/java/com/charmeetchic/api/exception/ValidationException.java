package com.charmeetchic.api.exception;

/**
 * Violación de una regla de negocio detectada en los servicios (rango de fechas invertido,
 * transición de estado inválida, producto inactivo...). Las validaciones de formato de los DTOs
 * las hace Bean Validation y también terminan en HTTP 400.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
