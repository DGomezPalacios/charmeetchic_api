package com.charmeetchic.api.repository;

import com.charmeetchic.api.entity.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** Para descartar mensajes duplicados (idempotencia del consumidor). */
    boolean existsByEventId(String eventId);

    Page<StockMovement> findByProductId(Long productId, Pageable pageable);
}
