package com.charmeetchic.rabbitadmin.service;

import com.charmeetchic.rabbitadmin.client.RabbitManagementClient;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.NodeDetails;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.QueueDetails;
import com.charmeetchic.rabbitadmin.config.AppProperties;
import com.charmeetchic.rabbitadmin.dto.AlertInfo.Alert;
import com.charmeetchic.rabbitadmin.dto.AlertInfo.Severity;
import com.charmeetchic.rabbitadmin.dto.AlertInfo.Snapshot;
import com.charmeetchic.rabbitadmin.dto.AlertInfo.Type;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alertas operacionales de RabbitMQ, calculadas con la API de administración (colas y nodos).
 *
 * <table>
 *   <tr><th>Regla</th><th>Severidad</th></tr>
 *   <tr><td>DLQ con mensajes (nombre termina en .dlq)</td><td>CRITICA</td></tr>
 *   <tr><td>Cola principal llena (100 % de x-max-length: ya descarta los más antiguos)</td><td>CRITICA</td></tr>
 *   <tr><td>Cola principal sobre el % configurado de x-max-length</td><td>ADVERTENCIA</td></tr>
 *   <tr><td>Cola principal con mensajes y 0 consumidores</td><td>ADVERTENCIA</td></tr>
 *   <tr><td>Nodo del clúster caído</td><td>CRITICA</td></tr>
 *   <tr><td>Alarma de memoria o de disco en un nodo</td><td>CRITICA</td></tr>
 *   <tr><td>API de administración inalcanzable</td><td>CRITICA</td></tr>
 * </table>
 *
 * <p>El log registra {@code ALERTA} solo cuando un problema APARECE y {@code ALERTA RESUELTA} cuando
 * desaparece (no repite la misma línea cada 30 s). El estado actual queda en {@link #current()}.
 */
@Slf4j
@Service
public class AlertService {

    private final RabbitManagementClient management;
    private final int usagePercent;
    private final String deadLetterSuffix;

    /** Alertas activas por clave (tipo|recurso), para conservar el "desde" y detectar cambios. */
    private Map<String, Alert> active = new LinkedHashMap<>();
    private Instant checkedAt;

    public AlertService(RabbitManagementClient management, AppProperties props) {
        this.management = management;
        this.usagePercent = props.alerts().queueUsagePercent();
        this.deadLetterSuffix = props.alerts().deadLetterSuffix();
    }

    /** Consulta RabbitMQ, recalcula las alertas y registra en el log las que aparecen o se resuelven. */
    public synchronized Snapshot refresh() {
        Instant now = Instant.now();
        List<Alert> found;
        try {
            found = evaluate(management.listQueues(), management.listNodes(), now);
        } catch (RuntimeException e) {
            found = List.of(new Alert(Severity.CRITICA, Type.BROKER_INALCANZABLE, "management-api",
                    "No se pudo consultar la API de administración de RabbitMQ: " + e.getMessage(), now));
        }

        Map<String, Alert> next = new LinkedHashMap<>();
        for (Alert alert : found) {
            Alert previous = active.get(alert.key());
            if (previous == null) {
                log.warn("ALERTA [{}] {} en '{}': {}", alert.severity(), alert.type(), alert.resource(), alert.message());
                next.put(alert.key(), alert);
            } else {
                // mismo problema: se conserva desde cuándo está activo, con el mensaje actualizado
                next.put(alert.key(), new Alert(alert.severity(), alert.type(), alert.resource(), alert.message(),
                        previous.since()));
            }
        }
        active.keySet().stream().filter(key -> !next.containsKey(key)).forEach(key -> {
            Alert gone = active.get(key);
            log.info("ALERTA RESUELTA {} en '{}' (estaba activa desde {})", gone.type(), gone.resource(), gone.since());
        });
        active = next;
        checkedAt = now;
        return current();
    }

    /** Última revisión (sin consultar RabbitMQ de nuevo). */
    public synchronized Snapshot current() {
        List<Alert> alerts = active.values().stream()
                .sorted(Comparator.comparing(Alert::severity).thenComparing(Alert::resource))
                .toList();
        return new Snapshot(checkedAt, alerts.isEmpty(), alerts.size(), alerts);
    }

    /** Reglas puras (sin estado): fáciles de probar con datos de ejemplo. */
    List<Alert> evaluate(List<QueueDetails> queues, List<NodeDetails> nodes, Instant now) {
        List<Alert> alerts = new ArrayList<>();
        for (QueueDetails q : queues) {
            int messages = q.messages() == null ? 0 : q.messages();
            int consumers = q.consumers() == null ? 0 : q.consumers();
            if (q.name().endsWith(deadLetterSuffix)) {
                if (messages > 0) {
                    alerts.add(new Alert(Severity.CRITICA, Type.DLQ_CON_MENSAJES, q.name(),
                            messages + " mensaje(s) fallido(s) esperando revisión", now));
                }
                continue;
            }
            Integer max = maxLength(q);
            if (max != null && max > 0) {
                int percent = (int) Math.floor(messages * 100.0 / max);
                if (percent >= 100) {
                    alerts.add(new Alert(Severity.CRITICA, Type.COLA_CERCA_DEL_MAXIMO, q.name(),
                            "llena (" + messages + "/" + max + "): se están descartando los mensajes más antiguos", now));
                } else if (percent >= usagePercent) {
                    alerts.add(new Alert(Severity.ADVERTENCIA, Type.COLA_CERCA_DEL_MAXIMO, q.name(),
                            messages + "/" + max + " mensajes (" + percent + " % del máximo)", now));
                }
            }
            if (messages > 0 && consumers == 0) {
                alerts.add(new Alert(Severity.ADVERTENCIA, Type.COLA_SIN_CONSUMIDORES, q.name(),
                        messages + " mensaje(s) pendientes y ningún consumidor conectado", now));
            }
        }
        for (NodeDetails n : nodes) {
            if (!n.running()) {
                alerts.add(new Alert(Severity.CRITICA, Type.NODO_CAIDO, n.name(), "el nodo no está corriendo", now));
                continue;
            }
            if (n.memAlarm()) {
                alerts.add(new Alert(Severity.CRITICA, Type.ALARMA_MEMORIA, n.name(),
                        "memoria sobre el límite (" + mb(n.memUsed()) + " / " + mb(n.memLimit())
                                + "): RabbitMQ bloquea a los productores", now));
            }
            if (n.diskFreeAlarm()) {
                alerts.add(new Alert(Severity.CRITICA, Type.ALARMA_DISCO, n.name(),
                        "disco libre bajo el mínimo (" + mb(n.diskFree()) + " < " + mb(n.diskFreeLimit())
                                + "): RabbitMQ bloquea a los productores", now));
            }
        }
        return alerts;
    }

    private static Integer maxLength(QueueDetails q) {
        Object value = q.arguments() == null ? null : q.arguments().get("x-max-length");
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String mb(Long bytes) {
        return bytes == null ? "?" : (bytes / (1024 * 1024)) + " MiB";
    }
}
