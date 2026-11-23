package com.charmeetchic.api.enums;

/**
 * Fallo que se puede pedir a propósito al solicitar un reporte asíncrono, para mostrar en la demo cómo el
 * consumidor reintenta y cuándo manda un mensaje a la DLQ. Solo funciona si
 * {@code app.messaging.demo.simulated-failures-enabled=true}.
 */
public enum SimulatedFailure {
    /** Error transitorio en TODOS los intentos: se reintenta hasta agotar max-attempts y termina en la DLQ. */
    TRANSITORIO,
    /** Error transitorio solo en el primer intento: el reintento lo procesa bien (ACK). */
    TRANSITORIO_UNA_VEZ,
    /** Mensaje inválido: NO se reintenta, va directo a la DLQ. */
    INVALIDO
}
