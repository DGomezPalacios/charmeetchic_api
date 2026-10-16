package com.charmeetchic.api.enums;

/** Estado de un reporte generado de forma asíncrona. */
public enum ReportStatus {
    /** Solicitado; el mensaje está en la cola o se está reintentando. */
    PENDIENTE,
    /** El consumidor lo generó; el resultado está disponible. */
    COMPLETADO,
    /** El mensaje terminó en la DLQ; {@code error} explica por qué. */
    FALLIDO
}
