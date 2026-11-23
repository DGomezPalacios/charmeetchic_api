package com.charmeetchic.api.controller;

import com.charmeetchic.api.messaging.consumer.ConsumerControlService;
import com.charmeetchic.api.messaging.consumer.ConsumerControlService.ConsumerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Control de los consumidores de RabbitMQ de esta API (demo de durabilidad). URL base: {@code /api/messaging}.
 * Solo ADMIN.
 */
@RestController
@RequestMapping("/messaging")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class MessagingController {

    private final ConsumerControlService consumerControlService;

    /** GET /api/messaging/consumers — ids, estado y colas de cada consumidor. */
    @GetMapping("/consumers")
    public ResponseEntity<List<ConsumerStatus>> list() {
        return ResponseEntity.ok(consumerControlService.list());
    }

    /** POST /api/messaging/consumers/{id}/pause — deja de consumir; los mensajes se acumulan en la cola. */
    @PostMapping("/consumers/{id}/pause")
    public ResponseEntity<ConsumerStatus> pause(@PathVariable String id) {
        return ResponseEntity.ok(consumerControlService.pause(id));
    }

    /** POST /api/messaging/consumers/{id}/resume — vuelve a consumir y procesa lo pendiente. */
    @PostMapping("/consumers/{id}/resume")
    public ResponseEntity<ConsumerStatus> resume(@PathVariable String id) {
        return ResponseEntity.ok(consumerControlService.resume(id));
    }
}
