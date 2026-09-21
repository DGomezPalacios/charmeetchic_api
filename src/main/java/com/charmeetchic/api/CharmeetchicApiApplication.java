package com.charmeetchic.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de la API de inventario de Charme et Chic.
 *
 * <p>Arranque: {@code mvn spring-boot:run}  ->  http://localhost:8080/api
 */
@SpringBootApplication
public class CharmeetchicApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CharmeetchicApiApplication.class, args);
    }
}
