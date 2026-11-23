package com.charmeetchic.api.service;

import com.charmeetchic.api.streaming.event.VentaEvent;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Historial de ventas del SEGUNDO consumer group sobre {@code ventas.eventos} ({@code historial-ventas}).
 *
 * <p>Existe para demostrar el modelo de consumo de Kafka:
 * <ul>
 *   <li><b>Entre grupos</b>: este grupo y {@code metricas-ventas} reciben CADA UNO todos los mensajes del
 *       tópico, con offsets propios. Pausar uno no afecta al otro (su lag sube solo).</li>
 *   <li><b>Dentro de un grupo</b>: las particiones se reparten. Este grupo tiene 1 consumidor, que recibe las 3
 *       particiones; {@code metricas-ventas} tiene 3, uno por partición. Por eso aquí se cuenta cuántos
 *       mensajes llegaron de cada partición.</li>
 * </ul>
 */
@Service
public class HistorialVentasService {

    static final int CAPACITY = 50;

    private final Deque<Registro> ultimas = new ArrayDeque<>();
    private final Map<Integer, Long> porParticion = new TreeMap<>();
    private long procesados;

    /** Una venta tal como la vio este grupo (con su partición y offset). */
    public record Registro(String orderId, String tipo, String origen, int unidades, BigDecimal total,
                           Instant timestamp, int particion, long offset) {
    }

    /** Lo que muestra {@code GET /api/streaming/historial}. */
    public record Resumen(String grupo, long procesados, Map<Integer, Long> porParticion, List<Registro> ultimas) {
    }

    public synchronized void registrar(VentaEvent event, int particion, long offset) {
        procesados++;
        porParticion.merge(particion, 1L, Long::sum);
        ultimas.addFirst(new Registro(event.orderId(), event.tipo().name(), event.origen().name(), event.unidades(),
                event.total(), event.timestamp(), particion, offset));
        while (ultimas.size() > CAPACITY) {
            ultimas.removeLast();
        }
    }

    public synchronized Resumen resumen(String grupo) {
        // TreeMap: las particiones salen ordenadas (0, 1, 2) en el JSON.
        return new Resumen(grupo, procesados, Collections.unmodifiableMap(new TreeMap<>(porParticion)),
                List.copyOf(ultimas));
    }
}
