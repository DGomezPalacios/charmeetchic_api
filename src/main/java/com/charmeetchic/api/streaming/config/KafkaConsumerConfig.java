package com.charmeetchic.api.streaming.config;

import com.charmeetchic.api.streaming.consumer.InvalidStreamingEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consumidores de Kafka y su manejo de errores. Infraestructura pura: los {@code @KafkaListener} solo
 * reciben el registro y delegan en los servicios de negocio.
 *
 * <p><b>Qué pasa con un mensaje que falla</b> (tres casos, todos terminan en el tópico {@code <original>.DLT}):
 * <ol>
 *   <li><b>JSON inválido</b>: el {@link ErrorHandlingDeserializer} atrapa el error de deserialización en vez de
 *       dejar que tumbe al consumidor en un bucle infinito (el registro "veneno" se volvería a leer para
 *       siempre). El listener ni se invoca: el manejador lo manda al .DLT con los bytes originales, sin
 *       reintentar.</li>
 *   <li><b>Evento que no pasa la validación</b> ({@link InvalidStreamingEventException}): tampoco se reintenta,
 *       porque el mensaje seguirá igual de inválido. Directo al .DLT.</li>
 *   <li><b>Error transitorio</b> (cualquier otra excepción): {@code retry.attempts} reintentos con
 *       {@code retry.backoff-ms} de espera; si siguen fallando, al .DLT.</li>
 * </ol>
 * El {@link DeadLetterPublishingRecoverer} agrega headers con el tópico, partición, offset y grupo originales y
 * el motivo del error ({@code kafka_dlt-exception-message}), visibles en Kafka UI. Después se confirma el
 * offset: el consumidor sigue con el siguiente mensaje y la partición no queda bloqueada.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    /** Paquete de los eventos que el JsonDeserializer acepta crear (no instancia clases arbitrarias). */
    static final String EVENTS_PACKAGE = "com.charmeetchic.api.streaming.event";

    /** Header con la causa raíz del error que agrega el recuperador a cada mensaje del .DLT. */
    public static final String DLT_REASON_HEADER = "x-dlt-motivo";

    /**
     * Fábrica de consumidores. Los deserializadores se configuran por NOMBRE de clase (no como instancias) para
     * que cada {@code @KafkaListener} pueda indicar en {@code properties} el tipo de su evento
     * ({@code spring.json.value.default.type}) o cambiar el deserializador (el monitor del .DLT lee texto).
     */
    @Bean
    public ConsumerFactory<String, Object> streamingConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        // Un grupo NUEVO empieza desde el mensaje más antiguo retenido (no se pierde lo publicado antes de que
        // existiera). Un grupo que ya tiene offsets confirmados sigue desde donde quedó.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Los offsets los confirma el contenedor de Spring DESPUÉS de procesar (o de mandar al .DLT), nunca un
        // temporizador: entrega "al menos una vez", ningún mensaje se da por leído sin haberse procesado.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, EVENTS_PACKAGE);
        // El tipo lo decide el listener (por tópico), no un header con el nombre de la clase del productor.
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Manejo de errores común a todos los listeners: reintentos con backoff fijo y luego {@code .DLT}.
     *
     * @param streamingKafkaTemplate publica en el .DLT los eventos que sí se deserializaron (JSON)
     * @param bytesKafkaTemplate     publica en el .DLT, tal cual, los bytes que no se pudieron deserializar
     */
    @Bean
    public DefaultErrorHandler streamingErrorHandler(KafkaTemplate<String, Object> streamingKafkaTemplate,
                                                     KafkaTemplate<String, byte[]> bytesKafkaTemplate,
                                                     StreamingProperties props) {
        // El orden importa: el recuperador usa el primer template cuyo tipo calce con el valor del registro.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesKafkaTemplate);
        templates.put(Object.class, streamingKafkaTemplate);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                // Misma partición que el original (los .DLT tienen al menos las mismas particiones).
                (rec, ex) -> new TopicPartition(props.topics().deadLetterOf(rec.topic()), rec.partition()));
        // El header estándar kafka_dlt-exception-message trae el mensaje de la capa de Spring ("Listener method
        // ... threw exception"); este agrega la causa REAL (validación, JSON roto, fallo transitorio).
        recoverer.setHeadersFunction((rec, ex) -> new RecordHeaders(new RecordHeader[]{
                new RecordHeader(DLT_REASON_HEADER, motivo(ex).getBytes(StandardCharsets.UTF_8))}));

        FixedBackOff backOff = new FixedBackOff(props.retry().backoffMs(), props.retry().attempts());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Además de los no recuperables por defecto (DeserializationException, MessageConversionException,
        // ClassCastException...): un evento inválido no se arregla reintentando.
        handler.addNotRetryableExceptions(InvalidStreamingEventException.class);
        handler.setRetryListeners(new LoggingRetryListener());
        return handler;
    }

    /**
     * Fábrica de los contenedores de {@code @KafkaListener}. Se llama {@code kafkaListenerContainerFactory} para
     * reemplazar a la de Spring Boot y ser la que usan todos los listeners por defecto.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> streamingConsumerFactory, DefaultErrorHandler streamingErrorHandler,
            StreamingProperties props) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(streamingConsumerFactory);
        factory.setCommonErrorHandler(streamingErrorHandler);
        // Con el streaming apagado (tests, Azure) los listeners existen pero no se conectan.
        factory.setAutoStartup(props.enabled());
        // Confirmar el offset de cada registro apenas se procesa: si la app se cae, al volver repite como mucho
        // el registro en curso (los consumidores descartan duplicados por eventId).
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    /** Deja en el log cada intento fallido y cada envío al .DLT con tópico, partición, offset, key y motivo. */
    static class LoggingRetryListener implements RetryListener {

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> rec, Exception ex, int deliveryAttempt) {
            log.warn("Kafka: falló el intento {} de {}-{}@{} key={}: {}", deliveryAttempt, rec.topic(),
                    rec.partition(), rec.offset(), rec.key(), motivo(ex));
        }

        @Override
        public void recovered(ConsumerRecord<?, ?> rec, Exception ex) {
            log.error("Kafka: {}-{}@{} key={} enviado al .DLT. Motivo: {}", rec.topic(), rec.partition(),
                    rec.offset(), rec.key(), motivo(ex));
        }

        @Override
        public void recoveryFailed(ConsumerRecord<?, ?> rec, Exception original, Exception failure) {
            log.error("Kafka: no se pudo enviar {}-{}@{} key={} al .DLT ({}); se volverá a intentar. Motivo original: {}",
                    rec.topic(), rec.partition(), rec.offset(), rec.key(), failure.getMessage(), motivo(original));
        }
    }

    /** Mensaje de la causa más profunda (la de la capa de Spring solo dice "listener failed"), acotado. */
    static String motivo(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getClass().getSimpleName() + ": " + root.getMessage();
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}
