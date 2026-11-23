package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.StreamingMetricsDTO.EventoReciente;
import com.charmeetchic.api.streaming.config.StreamingProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Últimos N eventos consumidos de Kafka (ventas, stock y .DLT), para el "feed" del dashboard. Guarda también
 * la partición y el offset: en la demo se ve cómo eventos de distintas órdenes caen en distintas particiones.
 *
 * <p>Lo escriben varios hilos a la vez (metricas-ventas abre 3 consumidores), por eso los métodos son
 * {@code synchronized}; el volumen es bajo y la sección crítica es mínima.
 */
@Service
public class EventosRecientesService {

    private final int capacity;
    private final Deque<EventoReciente> eventos = new ArrayDeque<>();

    public EventosRecientesService(StreamingProperties props) {
        this.capacity = props.metrics().recentEvents();
    }

    public synchronized void registrar(String tipo, String detalle, Instant timestamp, int particion, long offset) {
        eventos.addFirst(new EventoReciente(tipo, detalle, timestamp, particion, offset));
        while (eventos.size() > capacity) {
            eventos.removeLast();
        }
    }

    /** El más reciente primero. */
    public synchronized List<EventoReciente> ultimos() {
        return List.copyOf(eventos);
    }
}
