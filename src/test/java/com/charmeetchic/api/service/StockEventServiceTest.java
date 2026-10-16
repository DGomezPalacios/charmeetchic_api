package com.charmeetchic.api.service;

import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.enums.MovementType;
import com.charmeetchic.api.messaging.event.LowStockEvent;
import com.charmeetchic.api.messaging.event.StockMovementEvent;
import com.charmeetchic.api.messaging.producer.InventoryEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Reglas de qué eventos de inventario se generan. Umbral = 5 (stock bajo = menos de 5). */
class StockEventServiceTest {

    private static final int THRESHOLD = 5;

    private final InventoryEventPublisher publisher = mock(InventoryEventPublisher.class);
    private final StockEventService service = new StockEventService(publisher, THRESHOLD);

    @Test
    void todoCambioDeStockPublicaUnMovimientoQueCuadra() {
        service.stockChanged(product(20), 23, MovementType.VENTA, "orden #1");

        ArgumentCaptor<StockMovementEvent> captor = ArgumentCaptor.forClass(StockMovementEvent.class);
        verify(publisher).publishMovement(captor.capture());
        StockMovementEvent event = captor.getValue();
        assertThat(event.quantity()).isEqualTo(-3);
        assertThat(event.previousStock()).isEqualTo(23);
        assertThat(event.newStock()).isEqualTo(20);
        assertThat(event.isConsistent()).isTrue();
        assertThat(event.reference()).isEqualTo("orden #1");
        assertThat(event.eventId()).isNotBlank();
    }

    @Test
    void sinCambioDeStock_noPublicaNada() {
        service.stockChanged(product(10), 10, MovementType.AJUSTE_MANUAL, "ajuste");

        verifyNoInteractions(publisher);
    }

    @Test
    void cruzarElUmbralHaciaAbajo_publicaAlerta() {
        service.stockChanged(product(4), 6, MovementType.VENTA, "orden #2"); // 6 -> 4

        ArgumentCaptor<LowStockEvent> captor = ArgumentCaptor.forClass(LowStockEvent.class);
        verify(publisher).publishLowStock(captor.capture());
        assertThat(captor.getValue().stock()).isEqualTo(4);
        assertThat(captor.getValue().threshold()).isEqualTo(THRESHOLD);
    }

    @Test
    void justoEnElUmbral_cuentaComoCruce() {
        service.stockChanged(product(4), 5, MovementType.VENTA, "orden #3"); // 5 -> 4

        verify(publisher).publishLowStock(any());
    }

    @Test
    void productoQueYaEstabaBajo_noRepiteLaAlerta() {
        service.stockChanged(product(2), 4, MovementType.VENTA, "orden #4"); // 4 -> 2

        verify(publisher).publishMovement(any());
        verify(publisher, never()).publishLowStock(any());
    }

    @Test
    void quedarEnElUmbral_noEsStockBajo() {
        service.stockChanged(product(5), 9, MovementType.VENTA, "orden #5"); // 9 -> 5

        verify(publisher, never()).publishLowStock(any());
    }

    @Test
    void reponerStock_noPublicaAlerta() {
        service.stockChanged(product(10), 2, MovementType.CANCELACION_ORDEN, "cancelación"); // 2 -> 10

        verify(publisher).publishMovement(any());
        verify(publisher, never()).publishLowStock(any());
    }

    private static Product product(int currentStock) {
        return Product.builder().id(1L).sku("COL-PER-001").name("Collar Perla").stock(currentStock).build();
    }
}
