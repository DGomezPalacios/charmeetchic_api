package com.charmeetchic.api.streaming.consumer;

/**
 * Fallo TRANSITORIO simulado (solo demo, header {@code x-simular-fallo}): representa, por ejemplo, una base de
 * datos o un servicio externo que no responde por un momento.
 *
 * <p>Es recuperable: el {@code DefaultErrorHandler} lo reintenta con espera (backoff) y, si se agotan los
 * reintentos, recién ahí lo aparta en el .DLT.
 */
public class SimulatedStreamingFailureException extends RuntimeException {

    public SimulatedStreamingFailureException(String message) {
        super(message);
    }
}
