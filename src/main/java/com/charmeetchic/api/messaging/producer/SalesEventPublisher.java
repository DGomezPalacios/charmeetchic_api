package com.charmeetchic.api.messaging.producer;

import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.event.SaleCompletedEvent;
import org.springframework.stereotype.Component;

/**
 * Publicador del dominio ventas (exchange FANOUT {@code charmeetchic.ventas}).
 *
 * <p>Publica UN mensaje por venta; el fanout ignora la routing key y entrega una copia a cada cola suscrita.
 * Agregar un tercer suscriptor (p. ej. correo de confirmación) es solo unir otra cola al exchange: este
 * código y {@code OrderService} no cambian. Sale tras el commit, como el resto de los eventos.
 */
@Component
public class SalesEventPublisher {

    private final AfterCommitRabbitSender sender;
    private final MessagingProperties props;

    public SalesEventPublisher(AfterCommitRabbitSender sender, MessagingProperties props) {
        this.sender = sender;
        this.props = props;
    }

    public void publishSale(SaleCompletedEvent event) {
        sender.send(props.exchanges().ventas(), "", event, event.eventId());
    }
}
