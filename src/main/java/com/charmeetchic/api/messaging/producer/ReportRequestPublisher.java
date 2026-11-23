package com.charmeetchic.api.messaging.producer;

import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.event.ReportRequestedEvent;
import org.springframework.stereotype.Component;

/**
 * Publicador del dominio notificaciones (exchange topic {@code charmeetchic.notificaciones}).
 * Publica con la routing key concreta {@code notificacion.reporte.inventario}, que calza con el binding
 * {@code notificacion.reporte.*} de la cola {@code notificaciones.reportes}.
 */
@Component
public class ReportRequestPublisher {

    private final AfterCommitRabbitSender sender;
    private final MessagingProperties props;

    public ReportRequestPublisher(AfterCommitRabbitSender sender, MessagingProperties props) {
        this.sender = sender;
        this.props = props;
    }

    /** Solicitud de reporte -> cola {@code notificaciones.reportes}. Se envía tras el commit del registro. */
    public void publish(ReportRequestedEvent event) {
        sender.send(props.exchanges().notificaciones(), props.queues().reportes().publishKey(), event,
                event.reportId());
    }
}
