package com.charmeetchic.api.exception;

/**
 * Un componente del que depende la respuesta (p. ej. el consumidor RPC de consultas de stock) no contestó a
 * tiempo. Se traduce a HTTP 504 Gateway Timeout.
 */
public class GatewayTimeoutException extends RuntimeException {

    public GatewayTimeoutException(String message) {
        super(message);
    }
}
