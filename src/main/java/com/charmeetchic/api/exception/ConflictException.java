package com.charmeetchic.api.exception;

/**
 * La operación choca con el estado actual del recurso (SKU o nombre duplicado, categoría con
 * productos, orden que ya no se puede cancelar...). Se traduce a HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
