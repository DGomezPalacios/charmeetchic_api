package com.charmeetchic.api.messaging.consumer.ventas;

import com.charmeetchic.api.messaging.consumer.ManualAckProcessor;
import com.charmeetchic.api.messaging.event.SaleCompletedEvent;
import com.charmeetchic.api.service.SalesAuditService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Suscriptor 1 del fanout {@code charmeetchic.ventas} (cola {@code ventas.auditoria}): registra cada venta.
 * Recibe su PROPIA copia del evento; si este consumidor falla o se detiene, el de métricas sigue igual.
 */
@Component
public class SaleAuditConsumer {

    private final ManualAckProcessor processor;
    private final SalesAuditService auditService;

    public SaleAuditConsumer(ManualAckProcessor processor, SalesAuditService auditService) {
        this.processor = processor;
        this.auditService = auditService;
    }

    @RabbitListener(id = "consumidor-ventas-auditoria", queues = "${app.messaging.queues.ventas-auditoria.name}")
    public void onSale(Message message, Channel channel) {
        processor.process(message, channel, SaleCompletedEvent.class, (event, attempt) -> auditService.record(event));
    }
}
