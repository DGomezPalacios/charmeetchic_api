package com.charmeetchic.api.messaging.producer;

import com.charmeetchic.api.exception.MessagingUnavailableException;
import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.event.StockQueryReply;
import com.charmeetchic.api.messaging.event.StockQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Cliente de la consulta SÍNCRONA de stock (request-reply / RPC sobre RabbitMQ).
 *
 * <p><b>Cómo funciona</b> ({@link RabbitTemplate#convertSendAndReceiveAsType}):
 * <ol>
 *   <li>Publica la consulta en {@code charmeetchic.inventario} con routing key {@code inventario.consulta-stock}
 *       y la propiedad {@code reply_to = amq.rabbitmq.reply-to} (<b>direct reply-to</b>): RabbitMQ entrega la
 *       respuesta directo a este canal, sin crear una cola temporal por cada consulta.</li>
 *   <li>El hilo que atiende la petición HTTP <b>queda bloqueado esperando</b> la respuesta.</li>
 *   <li>Si llega antes de {@code app.messaging.rpc.timeout-ms}, se devuelve; si no, devuelve vacío (504).</li>
 * </ol>
 *
 * <p>Usa su PROPIO {@link RabbitTemplate} (no es un bean): así el timeout de respuesta solo afecta al RPC y el
 * template compartido de los publicadores asíncronos queda igual. (Declararlo como bean reemplazaría el que
 * crea Spring Boot.)
 */
@Slf4j
@Component
public class StockQueryClient implements DisposableBean {

    private final RabbitTemplate rpcTemplate;
    private final MessagingProperties props;

    @Autowired
    public StockQueryClient(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
                            MessagingProperties props) {
        this(createTemplate(connectionFactory, jsonMessageConverter, props.rpc().timeoutMs()), props);
    }

    /** Para tests: recibe el template ya armado. */
    StockQueryClient(RabbitTemplate rpcTemplate, MessagingProperties props) {
        this.rpcTemplate = rpcTemplate;
        this.props = props;
    }

    private static RabbitTemplate createTemplate(ConnectionFactory cf, MessageConverter converter, long timeoutMs) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(converter);
        template.setReplyTimeout(timeoutMs);
        template.setUseDirectReplyToContainer(true);
        return template;
    }

    /**
     * Envía la consulta y espera la respuesta.
     *
     * @return la respuesta, o vacío si no llegó dentro del timeout
     * @throws MessagingUnavailableException si RabbitMQ no está disponible o la mensajería está deshabilitada
     */
    public Optional<StockQueryReply> query(String sku) {
        if (!props.enabled()) {
            throw new MessagingUnavailableException("La mensajería está deshabilitada (app.messaging.enabled=false)",
                    null);
        }
        String messageId = UUID.randomUUID().toString();
        String exchange = props.exchanges().inventario();
        String routingKey = props.queues().consultaStock().routingKey();
        log.info("RPC id={} -> consulta de stock sku={} (espera máx. {} ms)", messageId, sku, props.rpc().timeoutMs());
        try {
            StockQueryReply reply = rpcTemplate.convertSendAndReceiveAsType(exchange, routingKey,
                    new StockQueryRequest(sku), message -> {
                        message.getMessageProperties().setMessageId(messageId);
                        return message;
                    }, new ParameterizedTypeReference<StockQueryReply>() {
                    });
            if (reply == null) {
                log.warn("RPC id={} sin respuesta en {} ms (¿consumidor detenido o caído?)", messageId,
                        props.rpc().timeoutMs());
            } else {
                log.info("RPC id={} <- respuesta: sku={} encontrado={} stock={} (respondió {})", messageId,
                        reply.sku(), reply.found(), reply.stock(), reply.respondedBy());
            }
            return Optional.ofNullable(reply);
        } catch (AmqpException e) {
            throw new MessagingUnavailableException("RabbitMQ no está disponible para la consulta de stock: "
                    + e.getMessage(), e);
        }
    }

    /** Cierra el contenedor de direct reply-to al apagar la app. */
    @Override
    public void destroy() {
        rpcTemplate.stop();
    }
}
