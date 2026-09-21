package com.charmeetchic.api.exception;

/** El recurso solicitado (producto, categoría, orden...) no existe. Se traduce a HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    /** Atajo: {@code new ResourceNotFoundException("Producto", 7)} -> "Producto con id 7 no encontrado". */
    public ResourceNotFoundException(String resource, Object id) {
        super(resource + " con id " + id + " no encontrado");
    }
}
