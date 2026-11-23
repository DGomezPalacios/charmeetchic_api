package com.charmeetchic.api.dto;

import com.charmeetchic.api.entity.SaleRecord;

import java.math.BigDecimal;
import java.time.Instant;

/** Respuestas de los dos suscriptores del fanout de ventas. */
public final class SalesDTO {

    private SalesDTO() {
    }

    /** Un registro de GET /api/reports/sales-audit (suscriptor ventas.auditoria). */
    public record SaleRecordDTO(Long id, String eventId, Long orderId, String userId, Integer lines, Integer units,
                                BigDecimal total, Instant occurredAt, Instant recordedAt) {

        public static SaleRecordDTO from(SaleRecord r) {
            return new SaleRecordDTO(r.getId(), r.getEventId(), r.getOrderId(), r.getUserId(), r.getLines(),
                    r.getUnits(), r.getTotal(), r.getOccurredAt(), r.getRecordedAt());
        }
    }

    /**
     * GET /api/reports/sales-metrics (suscriptor ventas.metricas): resumen en vivo desde que arrancó la app.
     *
     * @param since momento desde el que se cuenta (arranque de la app)
     */
    public record SalesMetricsDTO(long orders, long units, BigDecimal revenue, BigDecimal averageTicket,
                                  Instant lastSaleAt, Instant since) {
    }
}
