package com.charmeetchic.api.messaging.consumer.inventario;

import com.charmeetchic.api.messaging.consumer.ManualAckProcessor;
import com.charmeetchic.api.messaging.event.StockMovementEvent;
import com.charmeetchic.api.service.StockAuditService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de {@code inventario.movimientos}: guarda cada movimiento de stock en la tabla de auditoría.
 *
 * <p>Es el caso típico de error TRANSITORIO: si la BD no responde, {@link StockAuditService#record} lanza una
 * excepción de acceso a datos y el procesador reintenta. Un mensaje mal formado (sin sku, movimiento que no
 * cuadra...) no pasa la validación y va directo a la DLQ. Los duplicados se ignoran (idempotente).
 */
@Component
public class StockMovementConsumer {

    private final ManualAckProcessor processor;
    private final StockAuditService auditService;

    public StockMovementConsumer(ManualAckProcessor processor, StockAuditService auditService) {
        this.processor = processor;
        this.auditService = auditService;
    }

    /** Recibe el mensaje "crudo" + el canal: el ACK/NACK lo decide {@link ManualAckProcessor}. */
    @RabbitListener(id = "consumidor-movimientos", queues = "${app.messaging.queues.movimientos.name}")
    public void onStockMovement(Message message, Channel channel) {
        processor.process(message, channel, StockMovementEvent.class,
                (event, attempt) -> auditService.record(event));
    }
}
