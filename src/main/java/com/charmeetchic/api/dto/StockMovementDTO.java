package com.charmeetchic.api.dto;

import com.charmeetchic.api.entity.StockMovement;
import com.charmeetchic.api.enums.MovementType;

import java.time.Instant;

/** Un registro de la auditoría de stock (GET /api/reports/stock-movements). */
public record StockMovementDTO(
        Long id,
        String eventId,
        Long productId,
        String sku,
        String productName,
        MovementType type,
        Integer quantity,
        Integer previousStock,
        Integer newStock,
        String reference,
        Instant occurredAt,
        Instant recordedAt) {

    public static StockMovementDTO from(StockMovement m) {
        return new StockMovementDTO(m.getId(), m.getEventId(), m.getProductId(), m.getSku(), m.getProductName(),
                m.getType(), m.getQuantity(), m.getPreviousStock(), m.getNewStock(), m.getReference(),
                m.getOccurredAt(), m.getRecordedAt());
    }
}
