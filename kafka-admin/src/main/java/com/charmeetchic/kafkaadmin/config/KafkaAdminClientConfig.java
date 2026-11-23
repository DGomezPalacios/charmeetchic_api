package com.charmeetchic.kafkaadmin.config;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Un único {@link Admin} (AdminClient) para toda la app: es thread-safe y mantiene sus conexiones abiertas.
 * Crearlo NO conecta al clúster (la conexión se abre con la primera operación), así el servicio arranca aunque
 * Kafka todavía no esté listo. Spring lo cierra al apagar ({@code close}).
 */
@Configuration
public class KafkaAdminClientConfig {

    @Bean(destroyMethod = "close")
    public Admin kafkaAdminClient(AppProperties props) {
        int timeout = (int) props.kafka().timeoutMs();
        return Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafka().bootstrapServers(),
                AdminClientConfig.CLIENT_ID_CONFIG, "charmeetchic-kafka-admin",
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.min(timeout, 10_000),
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeout));
    }
}
