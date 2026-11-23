package com.charmeetchic.rabbitadmin.controller;

import com.charmeetchic.rabbitadmin.dto.ExchangeRequest;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.ExchangeInfo;
import com.charmeetchic.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exchanges. Solo traduce HTTP <-> DTO; la lógica está en {@link RabbitAdminService}. */
@RestController
@RequestMapping("/exchanges")
public class ExchangeController {

    private final RabbitAdminService service;

    public ExchangeController(RabbitAdminService service) {
        this.service = service;
    }

    /** GET /exchanges — sin el exchange por defecto ni los internos amq.*. */
    @GetMapping
    public List<ExchangeInfo> list() {
        return service.listExchanges();
    }

    /** POST /exchanges — 201 si se creó, 200 si ya existía igual, 409 si existe distinto. */
    @PostMapping
    public ResponseEntity<ExchangeInfo> create(@Valid @RequestBody ExchangeRequest request) {
        return QueueController.respond(service.createExchange(request));
    }

    /** DELETE /exchanges/{name} — 204 al borrar. */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        service.deleteExchange(name);
        return ResponseEntity.noContent().build();
    }
}
