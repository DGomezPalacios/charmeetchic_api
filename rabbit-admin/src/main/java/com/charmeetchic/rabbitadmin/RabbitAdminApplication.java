package com.charmeetchic.rabbitadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Microservicio administrador de RabbitMQ: API REST para crear, listar y borrar colas, exchanges y bindings.
 *
 * <p>Es una aplicación aparte de la API de inventario (otro proceso, otro contenedor, otro puerto) y no
 * comparte código con ella: solo comparten el broker de RabbitMQ y el proyecto de Firebase.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RabbitAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitAdminApplication.class, args);
    }
}
