package com.charmeetchic.api.streaming.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Productores de Kafka. Infraestructura pura: el negocio nunca usa estos beans directamente, solo
 * {@code StreamingEventPublisher}.
 *
 * <p>Garantías de entrega (iguales para los dos templates):
 * <ul>
 *   <li>{@code acks=all}: el líder confirma el envío recién cuando TODAS las réplicas en sincronía (ISR) lo
 *       escribieron; con {@code min.insync.replicas=2} eso son al menos 2 brokers. Un mensaje confirmado
 *       sobrevive a la caída de un broker.</li>
 *   <li>{@code enable.idempotence=true}: el broker descarta los duplicados que provocan los reintentos del
 *       propio productor (cada envío lleva id de productor + número de secuencia) y mantiene el orden por
 *       partición aun con 5 envíos en vuelo.</li>
 *   <li>{@code max.block.ms=3000} y {@code delivery.timeout.ms=30000}: la publicación ocurre tras el commit, en
 *       el hilo de la petición HTTP. Si Kafka no está, el envío falla en pocos segundos (queda un ERROR en el
 *       log) en vez de dejar al cliente esperando el minuto que trae Kafka por defecto.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(StreamingProperties.class)
public class KafkaProducerConfig {

    /** Propiedades comunes de los productores (brokers + garantías de entrega). */
    static Map<String, Object> producerProps(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        // Espera hasta 5 ms para agrupar mensajes en un mismo lote: más rendimiento con latencia imperceptible.
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return props;
    }

    /**
     * Productor de eventos tipados (VentaEvent, StockEvent) serializados a JSON con el mismo ObjectMapper de la
     * API (fechas ISO-8601). Sin headers de tipo: el consumidor no depende de los nombres de clases Java del
     * productor (decide el tipo por el tópico), así otro servicio en otro lenguaje puede leer y escribir.
     */
    @Bean
    public ProducerFactory<String, Object> streamingProducerFactory(KafkaProperties kafkaProperties,
                                                                    ObjectMapper objectMapper) {
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper.copy());
        valueSerializer.setAddTypeInfo(false);
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(
                producerProps(kafkaProperties), new StringSerializer(), valueSerializer);
        factory.setPhysicalCloseTimeout(5);
        return factory;
    }

    @Bean
    @Primary
    public KafkaTemplate<String, Object> streamingKafkaTemplate(ProducerFactory<String, Object> streamingProducerFactory) {
        return new KafkaTemplate<>(streamingProducerFactory);
    }

    /**
     * Productor de bytes crudos. Lo usa el {@code DeadLetterPublishingRecoverer} para copiar TAL CUAL al .DLT un
     * mensaje que ni siquiera se pudo deserializar (JSON roto), y la demo para publicar ese JSON roto a propósito.
     */
    @Bean
    public KafkaTemplate<String, byte[]> bytesKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> props = producerProps(kafkaProperties);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        DefaultKafkaProducerFactory<String, byte[]> factory = new DefaultKafkaProducerFactory<>(props);
        factory.setPhysicalCloseTimeout(5);
        return new KafkaTemplate<>(factory);
    }
}
