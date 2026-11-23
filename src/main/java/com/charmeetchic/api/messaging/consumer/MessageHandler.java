package com.charmeetchic.api.messaging.consumer;

/**
 * Lógica que un consumidor ejecuta con un mensaje ya convertido y validado.
 *
 * @param <T> tipo del mensaje (record del paquete {@code messaging.event})
 */
@FunctionalInterface
public interface MessageHandler<T> {

    /**
     * Procesa el mensaje. Terminar sin excepción = ACK.
     *
     * @param attempt número de intento, empezando en 1
     * @throws InvalidMessageException si el mensaje es inválido (va directo a la DLQ)
     * @throws Exception               cualquier otro error se considera transitorio y se reintenta
     */
    void handle(T payload, int attempt) throws Exception;

    /**
     * Se llama justo antes de mandar el mensaje a la DLQ, para dejar constancia en el negocio (p. ej. marcar
     * un reporte como FALLIDO). {@code payload} es null si el mensaje ni siquiera se pudo leer.
     */
    default void onDeadLetter(T payload, String reason) {
    }
}
