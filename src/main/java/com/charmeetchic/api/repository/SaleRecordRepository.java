package com.charmeetchic.api.repository;

import com.charmeetchic.api.entity.SaleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRecordRepository extends JpaRepository<SaleRecord, Long> {

    /** Para descartar mensajes duplicados (idempotencia del suscriptor). */
    boolean existsByEventId(String eventId);
}
