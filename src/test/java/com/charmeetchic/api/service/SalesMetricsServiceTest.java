package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.SalesDTO.SalesMetricsDTO;
import com.charmeetchic.api.messaging.event.SaleCompletedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SalesMetricsServiceTest {

    private final SalesMetricsService service = new SalesMetricsService();

    @Test
    void acumulaOrdenesUnidadesEIngresos() {
        service.register(sale("e-1", 3, "100.00"));
        service.register(sale("e-2", 1, "50.00"));

        SalesMetricsDTO m = service.snapshot();
        assertThat(m.orders()).isEqualTo(2);
        assertThat(m.units()).isEqualTo(4);
        assertThat(m.revenue()).isEqualByComparingTo("150.00");
        assertThat(m.averageTicket()).isEqualByComparingTo("75.00");
    }

    @Test
    void elMismoEventoDosVeces_seCuentaUnaSola() {
        assertThat(service.register(sale("e-1", 3, "100.00"))).isTrue();
        assertThat(service.register(sale("e-1", 3, "100.00"))).isFalse();

        assertThat(service.snapshot().orders()).isEqualTo(1);
    }

    @Test
    void sinVentas_ticketPromedioCero() {
        assertThat(service.snapshot().averageTicket()).isEqualByComparingTo("0");
    }

    private static SaleCompletedEvent sale(String id, int units, String total) {
        return new SaleCompletedEvent(id, 1L, "u", 1, units, new BigDecimal(total), Instant.now());
    }
}
