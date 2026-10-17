package com.charmeetchic.rabbitadmin.dto;

import java.time.Instant;
import java.util.List;

/** Alertas operacionales de RabbitMQ (GET /alerts). */
public final class AlertInfo {

    private AlertInfo() {
    }

    public enum Severity { CRITICA, ADVERTENCIA }

    public enum Type {
        /** Hay mensajes en una DLQ: algo falló y requiere revisión. */
        DLQ_CON_MENSAJES,
        /** Una cola principal superó el % configurado de su x-max-length (o está llena). */
        COLA_CERCA_DEL_MAXIMO,
        /** Una cola principal tiene mensajes y nadie la consume (consumidor caído o pausado). */
        COLA_SIN_CONSUMIDORES,
        /** Un nodo del clúster no está corriendo. */
        NODO_CAIDO,
        /** Alarma de memoria de RabbitMQ: los productores quedan bloqueados. */
        ALARMA_MEMORIA,
        /** Alarma de disco de RabbitMQ: los productores quedan bloqueados. */
        ALARMA_DISCO,
        /** No se pudo consultar la API de administración. */
        BROKER_INALCANZABLE
    }

    /**
     * @param resource cola o nodo afectado
     * @param since    desde cuándo está activa (se conserva entre revisiones)
     */
    public record Alert(Severity severity, Type type, String resource, String message, Instant since) {

        /** Identidad de la alerta: el mismo problema en el mismo recurso. */
        public String key() {
            return type + "|" + resource;
        }
    }

    /** @param ok true si no hay alertas */
    public record Snapshot(Instant checkedAt, boolean ok, int total, List<Alert> alerts) {
    }
}
