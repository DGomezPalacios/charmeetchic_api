package com.charmeetchic.api.streaming.consumer;

import com.charmeetchic.api.service.HistorialVentasService;
import com.charmeetchic.api.streaming.event.VentaEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;

/**
 * SEGUNDO consumer group sobre {@code ventas.eventos} ({@code historial-ventas}, 1 consumidor). Lee los mismos
 * mensajes que {@code metricas-ventas} pero con offsets propios: demuestra que dos grupos consumen el mismo
 * tópico de forma independiente. Delega en {@link HistorialVentasService}.
 */
@Component
public class HistorialVentasListener {

    public static final String ID = "historial-ventas";

    private final HistorialVentasService historial;
    private final StreamingEventValidator validator;

    public HistorialVentasListener(HistorialVentasService historial, StreamingEventValidator validator) {
        this.historial = historial;
        this.validator = validator;
    }

    @KafkaListener(id = ID,
            groupId = "${app.streaming.consumers.historial-ventas.group}",
            topics = "${app.streaming.topics.ventas.name}",
            concurrency = "${app.streaming.consumers.historial-ventas.concurrency}",
            properties = JsonDeserializer.VALUE_DEFAULT_TYPE + "=com.charmeetchic.api.streaming.event.VentaEvent")
    public void onVenta(ConsumerRecord<String, VentaEvent> rec) {
        historial.registrar(validator.validate(rec.value()), rec.partition(), rec.offset());
    }
}
