package com.charmeetchic.api.messaging.producer;

import com.charmeetchic.api.messaging.config.MessagingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Envía mensajes a RabbitMQ <b>solo después de que la transacción de BD se confirma</b>.
 *
 * <p><b>Por qué:</b> los servicios publican en medio de un método {@code @Transactional}. Si publicáramos de
 * inmediato y luego la transacción hiciera rollback (p. ej. falta stock en la segunda línea de una orden),
 * la auditoría registraría un movimiento que nunca ocurrió. Además, el consumidor de reportes buscaría en la
 * BD un registro que todavía no está confirmado.
 *
 * <p><b>Cómo:</b> si hay una transacción activa, se registra una {@link TransactionSynchronization} cuyo
 * {@code afterCommit()} hace el envío. Si la transacción hace rollback, {@code afterCommit()} nunca se llama
 * y el mensaje simplemente no existe. Sin transacción activa, se envía en el momento.
 *
 * <p><b>Si RabbitMQ está caído:</b> el envío falla DESPUÉS del commit, así que la operación de negocio ya
 * quedó guardada y el usuario recibe su respuesta normal; solo se registra un ERROR con el id del mensaje.
 * (Garantizar la entrega aun con el broker caído requiere el patrón "outbox", fuera del alcance de este
 * proyecto.)
 *
 * <p>Clase de infraestructura: la usan los publicadores de este paquete, nunca los servicios directamente.
 */
@Slf4j
@Component
public class AfterCommitRabbitSender {

    private final RabbitTemplate rabbitTemplate;
    private final boolean enabled;

    public AfterCommitRabbitSender(RabbitTemplate rabbitTemplate, MessagingProperties props) {
        this.rabbitTemplate = rabbitTemplate;
        this.enabled = props.enabled();
    }

    /**
     * Programa el envío de {@code payload} (se serializa a JSON) al {@code exchange} con {@code routingKey}.
     *
     * @param messageId id único del mensaje; se guarda en la propiedad AMQP {@code message_id} y aparece en
     *                  todos los logs de productor y consumidor
     */
    public void send(String exchange, String routingKey, Object payload, String messageId) {
        if (!enabled) {
            log.debug("Mensajería deshabilitada (app.messaging.enabled=false): no se publica id={}", messageId);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(exchange, routingKey, payload, messageId);
                }
            });
            log.debug("Mensaje id={} programado para publicarse tras el commit", messageId);
        } else {
            doSend(exchange, routingKey, payload, messageId);
        }
    }

    private void doSend(String exchange, String routingKey, Object payload, String messageId) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, message -> {
                message.getMessageProperties().setMessageId(messageId);
                return message;
            });
            log.info("Publicado id={} -> exchange={} routingKey={} tipo={}", messageId, exchange, routingKey,
                    payload.getClass().getSimpleName());
        } catch (AmqpException e) {
            log.error("No se pudo publicar id={} -> exchange={} routingKey={}: {}. La operación de negocio ya "
                    + "está guardada; el mensaje se pierde.", messageId, exchange, routingKey, e.getMessage());
        }
    }
}
