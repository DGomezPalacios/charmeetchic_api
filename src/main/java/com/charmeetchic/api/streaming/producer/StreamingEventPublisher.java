package com.charmeetchic.api.streaming.producer;

import com.charmeetchic.api.streaming.config.StreamingProperties;
import com.charmeetchic.api.streaming.event.StockEvent;
import com.charmeetchic.api.streaming.event.VentaEvent;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * ÚNICO punto por el que el negocio publica en Kafka. Decide tópico y key de cada evento; los servicios solo
 * construyen el evento y llaman a {@link #publishVenta} o {@link #publishStock}.
 *
 * <ul>
 *   <li>Venta -> {@code ventas.eventos}, key = orderId (eventos de una orden en orden, en la misma partición).</li>
 *   <li>Stock -> {@code inventario.stock}, key = sku (la compactación conserva el último stock de cada sku).</li>
 * </ul>
 *
 * <p>Antes de enviar se valida el evento (Bean Validation): un bug que arme un evento inválido se detecta aquí,
 * con un ERROR en el log, en vez de llenar el .DLT de los consumidores. Los métodos {@code *ForDemo} se
 * saltan esa validación A PROPÓSITO para que la demo pueda mostrar cómo el consumidor aparta lo defectuoso.
 */
@Slf4j
@Component
public class StreamingEventPublisher {

    /** Header que pide al consumidor de métricas simular un fallo transitorio (solo demo). */
    public static final String SIMULATED_FAILURE_HEADER = "x-simular-fallo";

    private final AfterCommitKafkaSender sender;
    private final KafkaTemplate<String, byte[]> bytesTemplate;
    private final Validator validator;
    private final StreamingProperties props;

    public StreamingEventPublisher(AfterCommitKafkaSender sender, KafkaTemplate<String, byte[]> bytesTemplate,
                                   Validator validator, StreamingProperties props) {
        this.sender = sender;
        this.bytesTemplate = bytesTemplate;
        this.validator = validator;
        this.props = props;
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    /** Publica una venta (creada o cancelada) tras el commit. */
    public void publishVenta(VentaEvent event) {
        if (skip(event, event.orderId())) {
            return;
        }
        sender.send(new ProducerRecord<>(ventasTopic(), event.orderId(), event));
    }

    /** Publica un cambio de stock tras el commit. */
    public void publishStock(StockEvent event) {
        if (skip(event, event.sku())) {
            return;
        }
        sender.send(new ProducerRecord<>(props.topics().stock().name(), event.sku(), event));
    }

    /** DEMO: publica una venta SIN validarla (p. ej. sin ítems y con total negativo) para que termine en el .DLT. */
    public void publishInvalidVentaForDemo(VentaEvent event) {
        sender.send(new ProducerRecord<>(ventasTopic(), event.orderId(), event));
    }

    /** DEMO: publica una venta válida marcada para que el consumidor de métricas falle (reintentos y luego .DLT). */
    public void publishVentaWithSimulatedFailureForDemo(VentaEvent event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(ventasTopic(), event.orderId(), event);
        record.headers().add(new RecordHeader(SIMULATED_FAILURE_HEADER, "true".getBytes(StandardCharsets.UTF_8)));
        sender.send(record);
    }

    /** DEMO: publica bytes que NO son JSON válido: el consumidor no puede ni deserializarlos. */
    public void publishRawForDemo(String key, String rawPayload) {
        String topic = ventasTopic();
        try {
            bytesTemplate.send(topic, key, rawPayload.getBytes(StandardCharsets.UTF_8))
                    .whenComplete((r, ex) -> {
                        if (ex == null) {
                            log.info("Kafka (demo): payload crudo en {} key={} -> partición {} offset {}", topic, key,
                                    r.getRecordMetadata().partition(), r.getRecordMetadata().offset());
                        } else {
                            log.error("Kafka (demo): no se pudo publicar el payload crudo key={}: {}", key,
                                    ex.getMessage());
                        }
                    });
        } catch (RuntimeException e) {
            log.error("Kafka (demo): no se pudo publicar el payload crudo key={}: {}", key, e.getMessage());
        }
    }

    private String ventasTopic() {
        return props.topics().ventas().name();
    }

    /** true si no se debe publicar: streaming apagado o evento inválido. */
    private boolean skip(Object event, String key) {
        if (!props.enabled()) {
            log.debug("Streaming deshabilitado (app.streaming.enabled=false): no se publica key={}", key);
            return true;
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            List<String> errors = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .toList();
            log.error("Kafka: evento {} key={} inválido, NO se publica: {}", event.getClass().getSimpleName(), key,
                    errors);
            return true;
        }
        return false;
    }
}
