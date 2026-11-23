package com.charmeetchic.api.streaming.consumer;

import com.charmeetchic.api.service.EventosRecientesService;
import com.charmeetchic.api.streaming.config.KafkaConsumerConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Consumer group {@code monitor-dlt} sobre los tópicos {@code .DLT}: deja en el log y en el feed del dashboard
 * cada mensaje que un consumidor apartó, con el grupo que falló y el motivo (headers que agrega el
 * {@code DeadLetterPublishingRecoverer}). No reprocesa nada: solo hace visible el error.
 *
 * <p>Lee el valor como TEXTO (no como JSON): en el .DLT puede haber justamente un JSON roto. Nunca lanza
 * excepciones, para que un mensaje raro en el .DLT no genere a su vez otro error.
 */
@Slf4j
@Component
public class DeadLetterMonitorListener {

    public static final String ID = "monitor-dlt";

    private final EventosRecientesService eventosRecientes;

    public DeadLetterMonitorListener(EventosRecientesService eventosRecientes) {
        this.eventosRecientes = eventosRecientes;
    }

    @KafkaListener(id = ID,
            groupId = "${app.streaming.consumers.monitor-dlt.group}",
            topics = {"${app.streaming.topics.ventas.name}${app.streaming.topics.dead-letter.suffix}",
                    "${app.streaming.topics.stock.name}${app.streaming.topics.dead-letter.suffix}"},
            concurrency = "${app.streaming.consumers.monitor-dlt.concurrency}",
            properties = ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS
                    + "=org.apache.kafka.common.serialization.StringDeserializer")
    public void onDeadLetter(ConsumerRecord<String, String> rec) {
        String grupo = header(rec, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
        String motivo = header(rec, KafkaConsumerConfig.DLT_REASON_HEADER);
        if ("?".equals(motivo)) {
            motivo = header(rec, KafkaHeaders.DLT_EXCEPTION_MESSAGE); // mensaje publicado por otro recuperador
        }
        String origen = header(rec, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        log.error("Kafka .DLT: mensaje apartado en {}-{}@{} key={} | tópico original={} grupo={} | motivo: {}",
                rec.topic(), rec.partition(), rec.offset(), rec.key(), origen, grupo, motivo);
        String detalle = "key " + rec.key() + " de " + origen + " (grupo " + grupo + "): " + recortar(motivo);
        eventosRecientes.registrar("DLT", detalle, Instant.ofEpochMilli(rec.timestamp()), rec.partition(),
                rec.offset());
    }

    private static String header(ConsumerRecord<?, ?> rec, String name) {
        Header header = rec.headers().lastHeader(name);
        return header == null || header.value() == null ? "?" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String recortar(String texto) {
        return texto.length() > 160 ? texto.substring(0, 160) + "..." : texto;
    }
}
