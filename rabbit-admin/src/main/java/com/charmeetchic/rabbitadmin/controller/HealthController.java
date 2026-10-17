package com.charmeetchic.rabbitadmin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** GET /health — público, para el healthcheck del contenedor. No consulta RabbitMQ. */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "charmeetchic-rabbit-admin", "timestamp", Instant.now());
    }
}
