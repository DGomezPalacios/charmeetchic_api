package com.charmeetchic.api.messaging.consumer;

/**
 * Error PERMANENTE: el mensaje está mal formado, no pasa Bean Validation o apunta a algo que no existe.
 * Reintentarlo daría el mismo resultado, así que {@link ManualAckProcessor} lo manda directo a la DLQ.
 *
 * <p>Cualquier otra excepción se considera TRANSITORIA (BD caída, timeout...) y se reintenta.
 */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
