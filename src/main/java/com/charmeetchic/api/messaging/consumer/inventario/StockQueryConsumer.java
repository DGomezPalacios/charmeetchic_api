package com.charmeetchic.api.messaging.consumer.inventario;

import com.charmeetchic.api.messaging.consumer.InvalidMessageException;
import com.charmeetchic.api.messaging.consumer.ManualAckProcessor;
import com.charmeetchic.api.messaging.event.StockQueryReply;
import com.charmeetchic.api.messaging.event.StockQueryRequest;
import com.charmeetchic.api.service.StockLookupService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumidor RPC de {@code inventario.consulta-stock}: lee la consulta, busca el stock y RESPONDE a la
 * dirección que trae el mensaje en {@code reply_to}, copiando el {@code correlation_id}.
 *
 * <p>La respuesta se envía a mano (en vez de con el valor de retorno del método) porque el ACK es manual y
 * pasa por {@link ManualAckProcessor}: primero se responde y recién después se hace ACK de la consulta.
 * Una consulta sin {@code reply_to} no tiene a quién responder: es inválida (NACK, sin DLQ en esta cola).
 */
@Slf4j
@Component
public class StockQueryConsumer {

    private final ManualAckProcessor processor;
    private final StockLookupService lookupService;
    private final RabbitTemplate rabbitTemplate;

    public StockQueryConsumer(ManualAckProcessor processor, StockLookupService lookupService,
                              RabbitTemplate rabbitTemplate) {
        this.processor = processor;
        this.lookupService = lookupService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(id = "consumidor-consulta-stock", queues = "${app.messaging.queues.consulta-stock.name}")
    public void onStockQuery(Message message, Channel channel) {
        MessageProperties request = message.getMessageProperties();
        processor.process(message, channel, StockQueryRequest.class, (query, attempt) -> {
            String replyTo = request.getReplyTo();
            if (replyTo == null || replyTo.isBlank()) {
                throw new InvalidMessageException("la consulta no trae reply_to: no hay a quién responder");
            }
            StockQueryReply reply = lookupService.lookup(query.sku(), Thread.currentThread().getName());
            // Exchange por defecto ("") + routing key = reply_to: la respuesta va directo al que preguntó.
            rabbitTemplate.convertAndSend("", replyTo, reply, response -> {
                response.getMessageProperties().setCorrelationId(request.getCorrelationId());
                return response;
            });
            log.info("Consulta de stock sku={} respondida (encontrado={}, stock={})", query.sku(), reply.found(),
                    reply.stock());
        });
    }
}
