package com.charmeetchic.api.service;

import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.enums.MovementType;
import com.charmeetchic.api.messaging.event.LowStockEvent;
import com.charmeetchic.api.messaging.event.StockMovementEvent;
import com.charmeetchic.api.messaging.producer.InventoryEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Decide QUÉ eventos de inventario se generan cuando cambia el stock de un producto. Es lógica de negocio
 * (por eso vive en {@code service}); el CÓMO se envían lo resuelve {@link InventoryEventPublisher}.
 *
 * <p>Reglas:
 * <ul>
 *   <li>Todo cambio de stock (venta, cancelación, ajuste, stock inicial) genera un movimiento de auditoría.</li>
 *   <li>La alerta de stock bajo se genera SOLO cuando el stock CRUZA el umbral hacia abajo
 *       ({@code antes >= umbral} y {@code después < umbral}). Vender otra unidad de un producto que ya estaba
 *       bajo no repite la alerta.</li>
 * </ul>
 *
 * <p>Se llama desde dentro de las transacciones de {@link OrderService} y {@link ProductService}; los
 * mensajes salen recién cuando esa transacción se confirma.
 */
@Slf4j
@Service
public class StockEventService {

    private final InventoryEventPublisher publisher;
    private final int lowStockThreshold;

    public StockEventService(InventoryEventPublisher publisher,
                             @Value("${app.inventory.low-stock-threshold:5}") int lowStockThreshold) {
        this.publisher = publisher;
        this.lowStockThreshold = lowStockThreshold;
    }

    /**
     * Registra que el stock de {@code product} pasó de {@code previousStock} a su valor actual.
     * Si no cambió, no hace nada.
     *
     * @param reference texto libre que explica el origen (p. ej. "orden #12")
     */
    public void stockChanged(Product product, int previousStock, MovementType type, String reference) {
        int newStock = product.getStock();
        if (newStock == previousStock) {
            return;
        }
        Instant now = Instant.now();
        publisher.publishMovement(new StockMovementEvent(UUID.randomUUID().toString(), product.getId(),
                product.getSku(), product.getName(), type, newStock - previousStock, previousStock, newStock,
                reference, now));

        if (crossesThreshold(previousStock, newStock)) {
            log.info("Producto id={} sku={} cruzó el umbral de stock bajo ({}): {} -> {}", product.getId(),
                    product.getSku(), lowStockThreshold, previousStock, newStock);
            publisher.publishLowStock(new LowStockEvent(UUID.randomUUID().toString(), product.getId(),
                    product.getSku(), product.getName(), newStock, lowStockThreshold, now));
        }
    }

    /** true solo si el stock estaba en o sobre el umbral y quedó debajo. */
    boolean crossesThreshold(int previousStock, int newStock) {
        return previousStock >= lowStockThreshold && newStock < lowStockThreshold;
    }
}
