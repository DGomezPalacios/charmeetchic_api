package com.charmeetchic.rabbitadmin.controller;

import com.charmeetchic.rabbitadmin.dto.QueueRequest;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.Declared;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.QueueInfo;
import com.charmeetchic.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Colas. Solo traduce HTTP <-> DTO; la lógica está en {@link RabbitAdminService}. */
@RestController
@RequestMapping("/queues")
public class QueueController {

    private final RabbitAdminService service;

    public QueueController(RabbitAdminService service) {
        this.service = service;
    }

    /** GET /queues — todas las colas del vhost, con mensajes y consumidores. */
    @GetMapping
    public List<QueueInfo> list() {
        return service.listQueues();
    }

    /** POST /queues — 201 si se creó, 200 si ya existía igual, 409 si existe distinta. */
    @PostMapping
    public ResponseEntity<QueueInfo> create(@Valid @RequestBody QueueRequest request) {
        return respond(service.createQueue(request));
    }

    /** DELETE /queues/{name}?ifUnused=false&ifEmpty=false — 204 al borrar. */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name,
                                       @RequestParam(defaultValue = "false") boolean ifUnused,
                                       @RequestParam(defaultValue = "false") boolean ifEmpty) {
        service.deleteQueue(name, ifUnused, ifEmpty);
        return ResponseEntity.noContent().build();
    }

    static <T> ResponseEntity<T> respond(Declared<T> declared) {
        return ResponseEntity.status(declared.created() ? HttpStatus.CREATED : HttpStatus.OK).body(declared.resource());
    }
}
