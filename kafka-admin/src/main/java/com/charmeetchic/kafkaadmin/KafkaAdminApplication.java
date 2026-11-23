package com.charmeetchic.kafkaadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Microservicio administrador de Kafka: API REST para tópicos (crear, listar, describir, modificar configs,
 * aumentar particiones, borrar) y consumer groups (listar, describir con lag, resetear offsets).
 *
 * <p>Aplicación aparte de la API de inventario (otro proceso, contenedor y puerto), igual que rabbit-admin:
 * solo comparten el clúster de Kafka y el proyecto de Firebase.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KafkaAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaAdminApplication.class, args);
    }
}
