package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.SalesDTO.SalesMetricsDTO;
import com.charmeetchic.api.messaging.event.SaleCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Suscriptor 2 del fanout de ventas: mantiene un resumen EN MEMORIA (órdenes, unidades, ingresos) desde que
 * arrancó la app. Es a propósito distinto del suscriptor 1 (que persiste): muestra que cada cola tiene su
 * propio consumidor y su propio trabajo con el MISMO evento.
 *
 * <p>Idempotente: recuerda los últimos {@value #REMEMBERED_EVENTS} eventIds y no cuenta dos veces un
 * mensaje entregado de nuevo. Al reiniciar la app el resumen vuelve a cero (los datos históricos están en
 * la auditoría y en los reportes de ventas).
 */
@Slf4j
@Service
public class SalesMetricsService {

    static final int REMEMBERED_EVENTS = 1000;

    private final Instant since = Instant.now();
    private final Set<String> recentEventIds = new LinkedHashSet<>(); // conserva el orden de llegada
    private long orders;
    private long units;
    private BigDecimal revenue = BigDecimal.ZERO.setScale(2);
    private Instant lastSaleAt;

    /** @return true si se contó, false si era un duplicado */
    public synchronized boolean register(SaleCompletedEvent event) {
        if (!recentEventIds.add(event.eventId())) {
            log.info("Métricas: venta eventId={} ya contada, duplicado ignorado", event.eventId());
            return false;
        }
        if (recentEventIds.size() > REMEMBERED_EVENTS) {
            Iterator<String> oldest = recentEventIds.iterator();
            oldest.next();
            oldest.remove();
        }
        orders++;
        units += event.units();
        revenue = revenue.add(event.total());
        lastSaleAt = event.occurredAt();
        log.info("Métricas de ventas actualizadas: {} órdenes, {} unidades, ingresos {}", orders, units, revenue);
        return true;
    }

    public synchronized SalesMetricsDTO snapshot() {
        BigDecimal average = orders == 0
                ? BigDecimal.ZERO.setScale(2)
                : revenue.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
        return new SalesMetricsDTO(orders, units, revenue, average, lastSaleAt, since);
    }
}
