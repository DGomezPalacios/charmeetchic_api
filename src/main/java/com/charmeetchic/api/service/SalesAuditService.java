package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.SalesDTO.SaleRecordDTO;
import com.charmeetchic.api.entity.SaleRecord;
import com.charmeetchic.api.messaging.event.SaleCompletedEvent;
import com.charmeetchic.api.repository.SaleRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Suscriptor 1 del fanout de ventas: deja cada venta registrada en la BD (idempotente). */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesAuditService {

    private final SaleRecordRepository repository;

    /** @return true si se guardó, false si era un duplicado */
    @Transactional
    public boolean record(SaleCompletedEvent event) {
        if (repository.existsByEventId(event.eventId())) {
            log.info("Venta eventId={} ya estaba registrada: duplicado ignorado", event.eventId());
            return false;
        }
        repository.save(SaleRecord.builder()
                .eventId(event.eventId())
                .orderId(event.orderId())
                .userId(event.userId())
                .lines(event.lines())
                .units(event.units())
                .total(event.total())
                .occurredAt(event.occurredAt())
                .build());
        log.info("Auditoría de ventas: orden #{} de {} ({} unidades, total {})", event.orderId(), event.userId(),
                event.units(), event.total());
        return true;
    }

    @Transactional(readOnly = true)
    public Page<SaleRecordDTO> getRecords(Pageable pageable) {
        return repository.findAll(pageable).map(SaleRecordDTO::from);
    }
}
