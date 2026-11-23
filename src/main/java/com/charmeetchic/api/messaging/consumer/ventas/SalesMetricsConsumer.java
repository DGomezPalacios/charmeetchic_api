package com.charmeetchic.api.messaging.consumer.ventas;

import com.charmeetchic.api.messaging.consumer.ManualAckProcessor;
import com.charmeetchic.api.messaging.event.SaleCompletedEvent;
import com.charmeetchic.api.service.SalesMetricsService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Suscriptor 2 del fanout {@code charmeetchic.ventas} (cola {@code ventas.metricas}): actualiza el resumen
 * de ventas consultable en GET /api/reports/sales-metrics.
 */
@Component
public class SalesMetricsConsumer {

    private final ManualAckProcessor processor;
    private final SalesMetricsService metricsService;

    public SalesMetricsConsumer(ManualAckProcessor processor, SalesMetricsService metricsService) {
        this.processor = processor;
        this.metricsService = metricsService;
    }

    @RabbitListener(id = "consumidor-ventas-metricas", queues = "${app.messaging.queues.ventas-metricas.name}")
    public void onSale(Message message, Channel channel) {
        processor.process(message, channel, SaleCompletedEvent.class,
                (event, attempt) -> metricsService.register(event));
    }
}
