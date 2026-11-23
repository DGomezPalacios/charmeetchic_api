package com.charmeetchic.api.streaming.consumer;

import java.util.List;

/**
 * El evento llegó, se pudo leer como JSON, pero NO pasa la validación (Bean Validation) del DTO.
 *
 * <p>Error NO recuperable: reintentar no lo arregla (el mensaje seguirá igual de inválido), así que el
 * {@code DefaultErrorHandler} lo manda directo al .DLT sin reintentos ni esperas.
 */
public class InvalidStreamingEventException extends RuntimeException {

    private final transient List<String> errors;

    public InvalidStreamingEventException(String eventType, List<String> errors) {
        super(eventType + " inválido: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
