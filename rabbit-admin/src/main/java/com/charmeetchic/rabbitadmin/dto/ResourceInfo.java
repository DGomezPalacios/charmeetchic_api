package com.charmeetchic.rabbitadmin.dto;

import java.util.Map;

/**
 * Respuestas de la API. Son DTOs propios: el controlador nunca expone clases de Spring AMQP ni de RabbitMQ.
 * {@code system = true} marca los recursos de la API de inventario (protegidos contra borrado).
 */
public final class ResourceInfo {

    private ResourceInfo() {
    }

    /** @param messages mensajes en la cola (null si RabbitMQ aún no calculó la estadística) */
    public record QueueInfo(String name, boolean durable, boolean autoDelete, Map<String, Object> arguments,
                            Integer messages, Integer consumers, boolean system) {
    }

    public record ExchangeInfo(String name, String type, boolean durable, boolean autoDelete, boolean system) {
    }

    public record BindingInfo(String exchange, String queue, String routingKey, boolean system) {
    }

    /**
     * Resultado de un POST: el recurso y si se creó ahora (201) o ya existía idéntico (200). Que repetir un
     * POST con los mismos datos no falle hace la API idempotente, igual que una declaración en RabbitMQ.
     */
    public record Declared<T>(T resource, boolean created) {
    }
}
