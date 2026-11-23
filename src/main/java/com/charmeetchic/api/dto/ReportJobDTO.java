package com.charmeetchic.api.dto;

import com.charmeetchic.api.entity.ReportJob;
import com.charmeetchic.api.enums.ReportStatus;
import com.charmeetchic.api.enums.SimulatedFailure;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;

/**
 * Estado de un reporte asíncrono (respuesta de POST /api/reports/inventory/async y de
 * GET /api/reports/async/{id}).
 *
 * @param result JSON del reporte, incrustado tal cual en la respuesta ({@link JsonRawValue}); null hasta
 *               que el estado sea COMPLETADO
 */
public record ReportJobDTO(
        String id,
        String reportType,
        ReportStatus status,
        String requestedBy,
        Instant requestedAt,
        Instant completedAt,
        @JsonRawValue String result,
        String error) {

    public static ReportJobDTO from(ReportJob job) {
        return new ReportJobDTO(job.getId(), job.getReportType(), job.getStatus(), job.getRequestedBy(),
                job.getRequestedAt(), job.getCompletedAt(), job.getResult(), job.getError());
    }

    /**
     * Cuerpo opcional de POST /api/reports/inventory/async.
     *
     * @param simularError solo para la demo: TRANSITORIO, TRANSITORIO_UNA_VEZ o INVALIDO
     */
    public record Request(SimulatedFailure simularError) {
    }
}
