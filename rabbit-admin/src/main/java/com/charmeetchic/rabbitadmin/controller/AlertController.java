package com.charmeetchic.rabbitadmin.controller;

import com.charmeetchic.rabbitadmin.dto.AlertInfo.Snapshot;
import com.charmeetchic.rabbitadmin.service.AlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Alertas operacionales de RabbitMQ. A través del gateway: GET /admin/alerts. */
@RestController
@RequestMapping("/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * GET /alerts — resultado de la última revisión programada.
     * GET /alerts?refresh=true — revisa RabbitMQ en este momento (útil en la demo para no esperar 30 s).
     */
    @GetMapping
    public Snapshot alerts(@RequestParam(defaultValue = "false") boolean refresh) {
        return refresh ? alertService.refresh() : alertService.current();
    }
}
