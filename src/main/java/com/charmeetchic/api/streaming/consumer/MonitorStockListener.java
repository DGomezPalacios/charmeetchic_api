package com.charmeetchic.api.streaming.consumer;

import com.charmeetchic.api.service.StockMonitorService;
import com.charmeetchic.api.streaming.event.StockEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumer group {@code monitor-stock} sobre el tópico COMPACTADO {@code inventario.stock}. Delega en
 * {@link StockMonitorService}.
 *
 * <p>Al recibir sus particiones vuelve al INICIO del tópico ({@code seekToBeginning}): el estado vive en memoria
 * y se pierde al reiniciar la API, pero como el tópico compactado guarda al menos el último stock de cada sku,
 * releerlo completo reconstruye el stock actual en segundos. Es el uso típico de un tópico compactado como
 * "tabla". (Los grupos de ventas, en cambio, siguen desde su último offset confirmado.)
 */
@Slf4j
@Component
public class MonitorStockListener extends AbstractConsumerSeekAware {

    public static final String ID = "monitor-stock";

    private final StockMonitorService monitor;
    private final StreamingEventValidator validator;

    public MonitorStockListener(StockMonitorService monitor, StreamingEventValidator validator) {
        this.monitor = monitor;
        this.validator = validator;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        super.onPartitionsAssigned(assignments, callback);
        log.info("Monitor de stock: particiones {} asignadas; se relee el tópico compactado desde el inicio",
                assignments.keySet());
        callback.seekToBeginning(assignments.keySet());
    }

    @KafkaListener(id = ID,
            groupId = "${app.streaming.consumers.monitor-stock.group}",
            topics = "${app.streaming.topics.stock.name}",
            concurrency = "${app.streaming.consumers.monitor-stock.concurrency}",
            properties = JsonDeserializer.VALUE_DEFAULT_TYPE + "=com.charmeetchic.api.streaming.event.StockEvent")
    public void onStock(ConsumerRecord<String, StockEvent> rec) {
        if (rec.value() == null) {
            // Tombstone: en un tópico compactado, key con valor null = "este sku ya no existe".
            monitor.eliminar(rec.key());
            return;
        }
        monitor.actualizar(validator.validate(rec.value()), rec.partition(), rec.offset());
    }
}
