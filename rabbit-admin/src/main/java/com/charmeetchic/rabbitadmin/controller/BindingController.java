package com.charmeetchic.rabbitadmin.controller;

import com.charmeetchic.rabbitadmin.dto.BindingRequest;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.BindingInfo;
import com.charmeetchic.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Bindings exchange -> cola. Solo traduce HTTP <-> DTO; la lógica está en {@link RabbitAdminService}. */
@RestController
@RequestMapping("/bindings")
public class BindingController {

    private final RabbitAdminService service;

    public BindingController(RabbitAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<BindingInfo> list() {
        return service.listBindings();
    }

    /** POST /bindings — 201 si se creó, 200 si ya existía. 404 si la cola o el exchange no existen. */
    @PostMapping
    public ResponseEntity<BindingInfo> create(@Valid @RequestBody BindingRequest request) {
        return QueueController.respond(service.createBinding(request));
    }

    /**
     * DELETE /bindings?exchange=...&queue=...&routingKey=... — 204 al borrar.
     * Va por parámetros (no por cuerpo) porque varios clientes HTTP descartan el cuerpo de un DELETE.
     */
    @DeleteMapping
    public ResponseEntity<Void> delete(@Valid BindingRequest request) {
        service.deleteBinding(request);
        return ResponseEntity.noContent().build();
    }
}
