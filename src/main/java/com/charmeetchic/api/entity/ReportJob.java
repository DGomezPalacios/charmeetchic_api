package com.charmeetchic.api.entity;

import com.charmeetchic.api.enums.ReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Solicitud de reporte asíncrono. Se crea PENDIENTE al recibir el POST; el consumidor de
 * {@code notificaciones.reportes} la pasa a COMPLETADO (con el resultado) o a FALLIDO.
 *
 * <p>El id es un UUID generado por la aplicación (no autoincremental) porque se devuelve al cliente en la
 * respuesta 202 y también viaja como {@code message_id} del mensaje AMQP.
 */
@Entity
@Table(name = "report_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportJob {

    /** Tamaño máximo del JSON del resultado y del mensaje de error. */
    public static final int MAX_RESULT_LENGTH = 100_000;
    public static final int MAX_ERROR_LENGTH = 1_000;

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "report_type", nullable = false, length = 50)
    private String reportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status;

    /** UID (Firebase) del ADMIN que lo pidió. */
    @Column(name = "requested_by", length = 128)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Resultado serializado como JSON (solo si COMPLETADO). */
    @Column(name = "result", length = MAX_RESULT_LENGTH)
    private String result;

    /** Motivo del fallo (solo si FALLIDO). */
    @Column(name = "error", length = MAX_ERROR_LENGTH)
    private String error;
}
