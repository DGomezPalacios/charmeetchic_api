package com.charmeetchic.api.streaming.config;

import com.charmeetchic.api.streaming.config.StreamingProperties.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declara los tópicos del sistema. Al arrancar, el {@link KafkaAdmin} de Spring Boot recorre los beans
 * {@link NewTopic}: crea los que faltan, agrega particiones si el yml pide más y (con
 * {@code spring.kafka.admin.modify-topic-configs=true}) corrige las configs que difieran.
 *
 * <p>Los nombres, particiones, réplicas y configs salen SOLO de {@code app.streaming.topics} en
 * application.yml, donde está justificado cada parámetro. Aquí no se escribe ningún valor "a mano".
 *
 * <p>Si el streaming está deshabilitado no se declara ningún tópico y el KafkaAdmin no se conecta a nada.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.streaming", name = "enabled", havingValue = "true")
public class KafkaTopicConfig {

    private final Topics topics;

    public KafkaTopicConfig(StreamingProperties props) {
        this.topics = props.topics();
    }

    /** ventas.eventos: log de ventas (cleanup=delete, 7 días). Key = orderId. */
    @Bean
    public NewTopic ventasTopic() {
        return build(topics.ventas());
    }

    /** inventario.stock: último stock por producto (cleanup=compact). Key = sku. */
    @Bean
    public NewTopic stockTopic() {
        return build(topics.stock());
    }

    /** ventas.eventos.DLT: ventas que no se pudieron procesar (JSON inválido, validación o reintentos agotados). */
    @Bean
    public NewTopic ventasDeadLetterTopic() {
        return buildDeadLetter(topics.ventas().name());
    }

    /** inventario.stock.DLT: cambios de stock que no se pudieron procesar. */
    @Bean
    public NewTopic stockDeadLetterTopic() {
        return buildDeadLetter(topics.stock().name());
    }

    private static NewTopic build(StreamingProperties.TopicProperties topic) {
        return TopicBuilder.name(topic.name())
                .partitions(topic.partitions())
                .replicas(topic.replicas())
                .configs(topic.configs())
                .build();
    }

    private NewTopic buildDeadLetter(String originalTopic) {
        StreamingProperties.DeadLetterProperties dlt = topics.deadLetter();
        return TopicBuilder.name(topics.deadLetterOf(originalTopic))
                .partitions(dlt.partitions())
                .replicas(dlt.replicas())
                .configs(dlt.configs())
                .build();
    }
}
