package com.charmeetchic.api.messaging.event;

import com.charmeetchic.api.enums.SimulatedFailure;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Mensaje de la cola {@code notificaciones.reportes}: pedido de generar un reporte en segundo plano.
 * Solo viaja el id del reporte; el consumidor lee y actualiza el registro en la BD.
 *
 * @param reportId     id del registro {@code ReportJob} creado al recibir el POST (también es el messageId)
 * @param simularError fallo pedido a propósito para la demo (null = procesar normal)
 */
public record ReportRequestedEvent(
        @NotBlank(message = "reportId es obligatorio") String reportId,
        @NotBlank(message = "reportType es obligatorio") String reportType,
        String requestedBy,
        @NotNull(message = "requestedAt es obligatorio") Instant requestedAt,
        SimulatedFailure simularError) {
}
