package com.charmeetchic.gateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/** GET /health — salud del PROPIO gateway (healthcheck del contenedor). /api/health es la del backend. */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of("status", "UP", "service", "charmeetchic-gateway", "timestamp", Instant.now()));
    }
}
