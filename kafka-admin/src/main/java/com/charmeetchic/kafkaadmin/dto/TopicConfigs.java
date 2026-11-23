package com.charmeetchic.kafkaadmin.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configs de tópico que esta API deja fijar, y la validación de sus valores. Lista blanca a propósito: un
 * administrador de la tienda no necesita tocar parámetros internos (y un error de tipeo en el nombre de una
 * config se rechaza con 400 en vez de ignorarse).
 */
public final class TopicConfigs {

    /** Claves permitidas. */
    public static final Set<String> ALLOWED = Set.of(
            "cleanup.policy",          // delete | compact | compact,delete
            "retention.ms",            // cuánto tiempo se guardan los mensajes (-1 = para siempre)
            "retention.bytes",         // tamaño máximo por partición (-1 = sin límite)
            "min.insync.replicas",     // réplicas que deben confirmar una escritura con acks=all
            "segment.ms",              // cada cuánto se cierra un segmento (la compactación solo actúa en cerrados)
            "min.compaction.lag.ms",   // edad mínima de un mensaje antes de poder compactarse
            "delete.retention.ms",     // cuánto se conservan los tombstones en un tópico compactado
            "min.cleanable.dirty.ratio",
            "max.message.bytes",
            "compression.type");

    private static final Set<String> NUMERIC = Set.of("retention.ms", "retention.bytes", "min.insync.replicas",
            "segment.ms", "min.compaction.lag.ms", "delete.retention.ms", "max.message.bytes");
    private static final Set<String> CLEANUP = Set.of("delete", "compact", "compact,delete", "delete,compact");
    private static final Set<String> COMPRESSION = Set.of("producer", "uncompressed", "gzip", "snappy", "lz4", "zstd");

    private TopicConfigs() {
    }

    /** Lista de errores (vacía si todo es válido). */
    public static List<String> errors(Map<String, String> configs) {
        List<String> errors = new ArrayList<>();
        if (configs == null) {
            return errors;
        }
        configs.forEach((key, value) -> {
            if (!ALLOWED.contains(key)) {
                errors.add("config no permitida: '" + key + "' (permitidas: " + String.join(", ", ALLOWED.stream().sorted().toList()) + ")");
            } else if (value == null || value.isBlank()) {
                errors.add(key + ": el valor no puede estar vacío");
            } else if (NUMERIC.contains(key) && !isLong(value)) {
                errors.add(key + ": debe ser un número entero");
            } else if (NUMERIC.contains(key) && Long.parseLong(value.trim()) < -1) {
                errors.add(key + ": no puede ser menor que -1");
            } else if (key.equals("cleanup.policy") && !CLEANUP.contains(value.trim())) {
                errors.add("cleanup.policy: debe ser delete, compact o compact,delete");
            } else if (key.equals("compression.type") && !COMPRESSION.contains(value.trim())) {
                errors.add("compression.type: valor no soportado");
            } else if (key.equals("min.cleanable.dirty.ratio") && !isRatio(value)) {
                errors.add("min.cleanable.dirty.ratio: debe ser un decimal entre 0 y 1");
            }
        });
        return errors;
    }

    private static boolean isLong(String value) {
        try {
            Long.parseLong(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isRatio(String value) {
        try {
            double d = Double.parseDouble(value.trim());
            return d >= 0 && d <= 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
