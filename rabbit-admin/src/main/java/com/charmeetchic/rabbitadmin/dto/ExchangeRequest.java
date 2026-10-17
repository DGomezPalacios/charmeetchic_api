package com.charmeetchic.rabbitadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/**
 * Cuerpo de {@code POST /exchanges}.
 *
 * <pre>{"name": "pedidos", "type": "topic"}</pre>
 *
 * <p>{@code type} es un String validado con patrón (y no un enum) para que un valor inválido dé un 400 con un
 * mensaje claro en lugar de un error genérico de "JSON mal formado".
 *
 * @param type direct, topic, fanout o headers (sin distinguir mayúsculas)
 */
public record ExchangeRequest(
        @NotBlank(message = "el nombre del exchange es obligatorio")
        @Size(max = Names.MAX_LENGTH, message = "el nombre no puede superar 255 caracteres")
        @Pattern(regexp = Names.NAME_PATTERN, message = "el nombre " + Names.NAME_MESSAGE)
        @Pattern(regexp = Names.NOT_AMQ_PATTERN, message = Names.NOT_AMQ_MESSAGE)
        String name,
        @NotBlank(message = "el tipo es obligatorio: direct, topic, fanout o headers")
        @Pattern(regexp = "(?i)^(direct|topic|fanout|headers)$", message = "type debe ser direct, topic, fanout o headers")
        String type,
        Boolean durable,
        Boolean autoDelete) {

    public ExchangeRequest {
        type = type == null ? null : type.trim().toLowerCase(Locale.ROOT);
        durable = durable == null || durable;
        autoDelete = autoDelete != null && autoDelete;
    }
}
