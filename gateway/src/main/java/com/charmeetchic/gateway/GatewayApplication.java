package com.charmeetchic.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * API Gateway: única puerta de entrada HTTP al sistema (detrás de Caddy, que pone el HTTPS).
 *
 * <ul>
 *   <li>Valida el ID token de Firebase y rechaza con 401 lo inválido ANTES de que llegue a los servicios.</li>
 *   <li>Enruta {@code /api/**} a la API de inventario y {@code /admin/**} a rabbit-admin.</li>
 *   <li>Resuelve CORS para el frontend en un solo lugar.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
