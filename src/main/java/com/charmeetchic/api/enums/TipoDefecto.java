package com.charmeetchic.api.enums;

/**
 * Evento defectuoso que puede publicar {@code POST /api/streaming/simulate?defectuoso=true} para mostrar el
 * manejo de errores de los consumidores de Kafka.
 */
public enum TipoDefecto {
    /** JSON válido que NO pasa la validación (sin ítems, total negativo): .DLT directo, sin reintentos. */
    VALIDACION,
    /** Bytes que ni siquiera son JSON: el ErrorHandlingDeserializer lo atrapa y va al .DLT, sin reintentos. */
    JSON,
    /** Venta válida que el consumidor de métricas no logra procesar: reintentos con backoff y luego .DLT. */
    TRANSITORIO
}
