package com.charmeetchic.api.exception;

/** RabbitMQ no está disponible para una operación que lo necesita en línea (RPC). Se traduce a HTTP 503. */
public class MessagingUnavailableException extends RuntimeException {

    public MessagingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
