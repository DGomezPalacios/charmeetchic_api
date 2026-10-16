package com.charmeetchic.api.messaging.producer;

import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.event.LowStockEvent;
import com.charmeetchic.api.messaging.event.StockMovementEvent;
import org.springframework.stereotype.Component;

/**
 * Publicador del dominio inventario (exchange direct {@code charmeetchic.inventario}).
 *
 * <p>Es lo único que los servicios de negocio conocen de la mensajería: llaman a un método con un evento y
 * no saben de exchanges, routing keys ni RabbitTemplate. Los nombres salen de {@link MessagingProperties}.
 */
@Component
public class InventoryEventPublisher {

    private final AfterCommitRabbitSender sender;
    private final MessagingProperties props;

    public InventoryEventPublisher(AfterCommitRabbitSender sender, MessagingProperties props) {
        this.sender = sender;
        this.props = props;
    }

    /** Movimiento de stock -> cola {@code inventario.movimientos} (auditoría). */
    public void publishMovement(StockMovementEvent event) {
        sender.send(props.exchanges().inventario(), props.queues().movimientos().publishKey(), event, event.eventId());
    }

    /** Producto que cruzó el umbral de stock mínimo -> cola {@code inventario.stock-bajo}. */
    public void publishLowStock(LowStockEvent event) {
        sender.send(props.exchanges().inventario(), props.queues().stockBajo().publishKey(), event, event.eventId());
    }
}
