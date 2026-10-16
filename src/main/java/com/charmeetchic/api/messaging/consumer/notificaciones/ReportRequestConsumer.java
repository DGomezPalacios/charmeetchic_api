package com.charmeetchic.api.messaging.consumer.notificaciones;

import com.charmeetchic.api.enums.SimulatedFailure;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.consumer.InvalidMessageException;
import com.charmeetchic.api.messaging.consumer.ManualAckProcessor;
import com.charmeetchic.api.messaging.consumer.MessageHandler;
import com.charmeetchic.api.messaging.event.ReportRequestedEvent;
import com.charmeetchic.api.service.AsyncReportService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Component;

/**
 * Consumidor de {@code notificaciones.reportes}: genera el reporte de inventario solicitado por POST.
 *
 * <ul>
 *   <li>Si el reporte no existe en la BD -> mensaje inválido -> DLQ sin reintentar.</li>
 *   <li>Cualquier otro error -> transitorio -> reintento; al agotar los intentos -> DLQ.</li>
 *   <li>Antes de ir a la DLQ el reporte se marca FALLIDO, así el GET muestra el motivo.</li>
 * </ul>
 *
 * <p><b>Fallos simulados (demo):</b> si el mensaje trae {@code simularError} y
 * {@code app.messaging.demo.simulated-failures-enabled=true}, se fuerza el error pedido. Es la forma
 * controlada de mostrar reintentos y DLQ sin apagar la base de datos.
 */
@Slf4j
@Component
public class ReportRequestConsumer {

    private final ManualAckProcessor processor;
    private final AsyncReportService reportService;
    private final boolean simulatedFailuresEnabled;

    public ReportRequestConsumer(ManualAckProcessor processor, AsyncReportService reportService,
                                 MessagingProperties props) {
        this.processor = processor;
        this.reportService = reportService;
        this.simulatedFailuresEnabled = props.demo().simulatedFailuresEnabled();
    }

    @RabbitListener(id = "consumidor-reportes", queues = "${app.messaging.queues.reportes.name}")
    public void onReportRequested(Message message, Channel channel) {
        processor.process(message, channel, ReportRequestedEvent.class, new MessageHandler<>() {
            @Override
            public void handle(ReportRequestedEvent event, int attempt) {
                simulateFailureIfRequested(event, attempt);
                try {
                    reportService.generate(event.reportId());
                } catch (ResourceNotFoundException e) {
                    throw new InvalidMessageException("el reporte " + event.reportId() + " no existe", e);
                }
            }

            @Override
            public void onDeadLetter(ReportRequestedEvent event, String reason) {
                if (event != null) {
                    reportService.markFailed(event.reportId(), reason);
                }
            }
        });
    }

    /** Lanza el error pedido en {@code simularError} (solo si la demo lo permite). */
    void simulateFailureIfRequested(ReportRequestedEvent event, int attempt) {
        SimulatedFailure failure = event.simularError();
        if (failure == null) {
            return;
        }
        if (!simulatedFailuresEnabled) {
            log.warn("Reporte {} pide simularError={} pero los fallos simulados están deshabilitados: se ignora",
                    event.reportId(), failure);
            return;
        }
        switch (failure) {
            case INVALIDO -> throw new InvalidMessageException("fallo simulado INVALIDO (demo)");
            case TRANSITORIO -> throw new TransientDataAccessResourceException(
                    "fallo simulado TRANSITORIO en el intento " + attempt + " (demo)");
            case TRANSITORIO_UNA_VEZ -> {
                if (attempt == 1) {
                    throw new TransientDataAccessResourceException(
                            "fallo simulado TRANSITORIO_UNA_VEZ en el intento 1 (demo); el reintento funcionará");
                }
            }
        }
    }
}
