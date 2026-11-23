package com.charmeetchic.api.streaming.producer;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Envía a Kafka <b>solo después de que la transacción de BD se confirma</b> (misma regla que
 * {@code AfterCommitRabbitSender} para RabbitMQ): si la orden hace rollback, el evento nunca existe.
 *
 * <p>El envío es asíncrono: {@code send()} devuelve un futuro y el resultado (partición y offset asignados, o
 * el error) se registra en el log cuando el broker responde. Si Kafka no está disponible, la operación de
 * negocio ya quedó guardada y el usuario recibe su respuesta normal; solo queda un ERROR con la key.
 *
 * <p>Clase de infraestructura: la usa {@link StreamingEventPublisher}, nunca los servicios.
 */
@Slf4j
@Component
public class AfterCommitKafkaSender {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AfterCommitKafkaSender(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Programa el envío de {@code record} para después del commit (o lo envía ya si no hay transacción). */
    public void send(ProducerRecord<String, Object> record) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(record);
                }
            });
            log.debug("Evento Kafka key={} programado para publicarse en {} tras el commit", record.key(),
                    record.topic());
        } else {
            doSend(record);
        }
    }

    void doSend(ProducerRecord<String, Object> record) {
        try {
            kafkaTemplate.send(record).whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("Kafka: publicado {} key={} -> partición {} offset {}", record.topic(), record.key(),
                            result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                } else {
                    logFailure(record, ex);
                }
            });
        } catch (RuntimeException e) {
            // send() lanza de inmediato si no obtiene los metadatos del clúster en max.block.ms (Kafka caído).
            logFailure(record, e);
        }
    }

    private static void logFailure(ProducerRecord<String, Object> record, Throwable ex) {
        log.error("Kafka: no se pudo publicar en {} key={}: {}. La operación de negocio ya está guardada.",
                record.topic(), record.key(), ex.getMessage());
    }
}
