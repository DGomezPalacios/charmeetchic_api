package com.charmeetchic.rabbitadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Binding exchange -> cola. Cuerpo de {@code POST /bindings} y parámetros de {@code DELETE /bindings}.
 *
 * <pre>{"exchange": "charmeetchic.inventario", "queue": "auditoria.copia", "routingKey": "inventario.movimientos"}</pre>
 *
 * <p>Que la cola y el exchange EXISTAN no se puede saber con Bean Validation (hay que preguntarle al broker):
 * lo revisa {@code RabbitAdminService} y responde 404 si falta alguno.
 *
 * @param routingKey binding key; en topic admite '*' y '#'; vacía para fanout (por defecto "")
 */
public record BindingRequest(
        @NotBlank(message = "el exchange es obligatorio")
        @Size(max = Names.MAX_LENGTH, message = "exchange no puede superar 255 caracteres")
        @Pattern(regexp = Names.NAME_PATTERN, message = "exchange " + Names.NAME_MESSAGE)
        String exchange,
        @NotBlank(message = "la cola es obligatoria")
        @Size(max = Names.MAX_LENGTH, message = "queue no puede superar 255 caracteres")
        @Pattern(regexp = Names.NAME_PATTERN, message = "queue " + Names.NAME_MESSAGE)
        String queue,
        @NotNull
        @Size(max = Names.MAX_LENGTH, message = "routingKey no puede superar 255 caracteres")
        @Pattern(regexp = Names.ROUTING_KEY_PATTERN, message = "routingKey " + Names.ROUTING_KEY_MESSAGE)
        String routingKey) {

    public BindingRequest {
        routingKey = routingKey == null ? "" : routingKey.trim();
    }
}
