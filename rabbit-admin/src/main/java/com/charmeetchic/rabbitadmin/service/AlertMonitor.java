package com.charmeetchic.rabbitadmin.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tarea programada: cada {@code app.alerts.interval-ms} (30 s por defecto) revisa RabbitMQ con
 * {@link AlertService}. Se apaga con {@code app.alerts.enabled=false} (los tests lo hacen).
 */
@Component
@ConditionalOnProperty(prefix = "app.alerts", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AlertMonitor {

    private final AlertService alertService;

    public AlertMonitor(AlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(initialDelayString = "${app.alerts.interval-ms}", fixedDelayString = "${app.alerts.interval-ms}")
    public void check() {
        alertService.refresh();
    }
}
