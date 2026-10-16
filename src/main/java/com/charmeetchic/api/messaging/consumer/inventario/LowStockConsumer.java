package com.charmeetchic.api.messaging.consumer.inventario;

import com.charmeetchic.api.messaging.consumer.ManualAckProcessor;
import com.charmeetchic.api.messaging.event.LowStockEvent;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de {@code inventario.stock-bajo}: emite la alerta de reposición.
 *
 * <p>En este proyecto la "notificación" es un WARN en el log, fácil de mostrar en la demo. Aquí se
 * enchufaría un correo o un mensaje a la dueña de la tienda sin tocar la lógica de órdenes ni de productos:
 * esa es la ventaja de desacoplar con una cola.
 */
@Slf4j
@Component
public class LowStockConsumer {

    private final ManualAckProcessor processor;

    public LowStockConsumer(ManualAckProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(id = "consumidor-stock-bajo", queues = "${app.messaging.queues.stock-bajo.name}")
    public void onLowStock(Message message, Channel channel) {
        processor.process(message, channel, LowStockEvent.class, (event, attempt) ->
                log.warn("ALERTA STOCK BAJO: '{}' (sku={}, id={}) quedó con {} unidades; el mínimo es {}. Reponer.",
                        event.productName(), event.sku(), event.productId(), event.stock(), event.threshold()));
    }
}
