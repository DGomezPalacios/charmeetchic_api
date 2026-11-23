package com.charmeetchic.api.messaging.event;

import com.charmeetchic.api.enums.MovementType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Regla de consistencia del movimiento: stock anterior + variación = stock nuevo y variación distinta de 0. */
class StockMovementEventTest {

    private static StockMovementEvent evento(Integer cantidad, Integer anterior, Integer nuevo) {
        return new StockMovementEvent("e-1", 1L, "SKU-1", "Aros", MovementType.values()[0],
                cantidad, anterior, nuevo, "orden #1", Instant.now());
    }

    @Test
    void movimientoQueCuadraEsConsistente() {
        assertThat(evento(-2, 10, 8).isConsistent()).isTrue();
    }

    @Test
    void movimientoQueNoCuadraOConCantidadCeroEsInconsistente() {
        assertThat(evento(-2, 10, 9).isConsistent()).isFalse();
        assertThat(evento(0, 10, 10).isConsistent()).isFalse();
    }

    @Test
    void camposNulosSeDeleganALosNotNull() {
        assertThat(evento(null, 10, 8).isConsistent()).isTrue();
        assertThat(evento(-2, null, 8).isConsistent()).isTrue();
        assertThat(evento(-2, 10, null).isConsistent()).isTrue();
    }
}
