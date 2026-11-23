package com.charmeetchic.api.streaming.consumer;

import com.charmeetchic.api.service.VentasMetricasService;
import com.charmeetchic.api.streaming.config.StreamingProperties;
import com.charmeetchic.api.streaming.event.VentaEvent;
import com.charmeetchic.api.streaming.producer.StreamingEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code metricas-ventas} sobre {@code ventas.eventos}: 3 consumidores (concurrency=3), uno por
 * partición, que alimentan las métricas en tiempo real.
 *
 * <p>Solo infraestructura: valida el evento y delega en {@link VentasMetricasService}. Los errores los resuelve
 * el {@code DefaultErrorHandler} (reintentos / .DLT), configurado en {@code KafkaConsumerConfig}.
 */
@Slf4j
@Component
public class VentasMetricasListener {

    public static final String ID = "metricas-ventas";

    private final VentasMetricasService metricas;
    private final StreamingEventValidator validator;
    private final boolean simulatedFailuresEnabled;

    public VentasMetricasListener(VentasMetricasService metricas, StreamingEventValidator validator,
                                  StreamingProperties props) {
        this.metricas = metricas;
        this.validator = validator;
        this.simulatedFailuresEnabled = props.demo().simulatedFailuresEnabled();
    }

    @KafkaListener(id = ID,
            groupId = "${app.streaming.consumers.metricas-ventas.group}",
            topics = "${app.streaming.topics.ventas.name}",
            concurrency = "${app.streaming.consumers.metricas-ventas.concurrency}",
            properties = JsonDeserializer.VALUE_DEFAULT_TYPE + "=com.charmeetchic.api.streaming.event.VentaEvent")
    public void onVenta(ConsumerRecord<String, VentaEvent> rec) {
        VentaEvent event = validator.validate(rec.value());
        if (simulatedFailuresEnabled && rec.headers().lastHeader(StreamingEventPublisher.SIMULATED_FAILURE_HEADER) != null) {
            // Demo: falla SIEMPRE -> 1 intento + reintentos con backoff -> .DLT. El grupo historial-ventas recibe el
            // mismo mensaje y lo procesa sin problemas: cada grupo es independiente.
            throw new SimulatedStreamingFailureException("fallo transitorio simulado (header "
                    + StreamingEventPublisher.SIMULATED_FAILURE_HEADER + ") en la orden " + event.orderId());
        }
        metricas.registrar(event, rec.partition(), rec.offset());
    }
}
