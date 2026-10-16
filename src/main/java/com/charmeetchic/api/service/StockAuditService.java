package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.StockMovementDTO;
import com.charmeetchic.api.entity.StockMovement;
import com.charmeetchic.api.messaging.event.StockMovementEvent;
import com.charmeetchic.api.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Auditoría de movimientos de stock: la escribe el consumidor de la cola y la lee el ADMIN. */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAuditService {

    private final StockMovementRepository repository;

    /**
     * Guarda el movimiento. Es idempotente: si ya existe un registro con el mismo {@code eventId} (mensaje
     * entregado dos veces), no hace nada.
     *
     * @return true si se guardó, false si era un duplicado
     */
    @Transactional
    public boolean record(StockMovementEvent event) {
        if (repository.existsByEventId(event.eventId())) {
            log.info("Movimiento eventId={} ya estaba registrado: duplicado ignorado", event.eventId());
            return false;
        }
        repository.save(StockMovement.builder()
                .eventId(event.eventId())
                .productId(event.productId())
                .sku(event.sku())
                .productName(event.productName())
                .type(event.type())
                .quantity(event.quantity())
                .previousStock(event.previousStock())
                .newStock(event.newStock())
                .reference(event.reference())
                .occurredAt(event.occurredAt())
                .build());
        log.info("Auditoría: {} sku={} {} -> {} ({}{}) ref='{}'", event.type(), event.sku(), event.previousStock(),
                event.newStock(), event.quantity() > 0 ? "+" : "", event.quantity(), event.reference());
        return true;
    }

    /** Movimientos, opcionalmente de un solo producto. */
    @Transactional(readOnly = true)
    public Page<StockMovementDTO> getMovements(Long productId, Pageable pageable) {
        Page<StockMovement> page = productId == null
                ? repository.findAll(pageable)
                : repository.findByProductId(productId, pageable);
        return page.map(StockMovementDTO::from);
    }
}
