package com.charmeetchic.api.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Comprobación de disponibilidad: {@code GET /api/health}. Público y sin dependencias de la base de datos. */
@RestController
@RequestMapping("/health")
public class HealthController {

    private final String applicationName;
    private final String version;

    public HealthController(@Value("${spring.application.name}") String applicationName,
                             @Value("${app.version}") String version) {
        this.applicationName = applicationName;
        this.version = version;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", applicationName,
                "version", version,
                "timestamp", Instant.now()));
    }
}
