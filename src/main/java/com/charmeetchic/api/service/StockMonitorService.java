package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.StreamingMetricsDTO.StockCritico;
import com.charmeetchic.api.streaming.event.StockEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock ACTUAL de cada producto, construido a partir del tópico compactado {@code inventario.stock}
 * (consumer group {@code monitor-stock}), y detección de problemas:
 *
 * <ul>
 *   <li><b>Quiebre de stock</b>: el producto llegó a 0 (WARN en el log y evento en el dashboard).</li>
 *   <li><b>Stock crítico</b>: el producto bajó del umbral {@code app.inventory.low-stock-threshold} (el mismo
 *       que usa {@code GET /products/low-stock}).</li>
 *   <li><b>Recuperación</b>: un producto crítico volvió a quedar sobre el umbral (cancelación o reposición).</li>
 * </ul>
 * Solo se avisa al CRUZAR un límite: vender otra unidad de un producto ya crítico no repite la alerta.
 *
 * <p>Como el tópico es compactado y el listener lo relee desde el inicio al arrancar, este mapa se reconstruye
 * solo después de un reinicio: un evento por sku basta para saber el stock actual.
 */
@Slf4j
@Service
public class StockMonitorService {

    private final EventosRecientesService eventosRecientes;
    private final int threshold;
    private final Map<String, Estado> stockPorSku = new HashMap<>();

    public StockMonitorService(EventosRecientesService eventosRecientes,
                               @Value("${app.inventory.low-stock-threshold:5}") int threshold) {
        this.eventosRecientes = eventosRecientes;
        this.threshold = threshold;
    }

    /** Estado conocido de un producto. */
    private record Estado(String nombre, int stock) {
    }

    /** Aplica el último stock informado de un producto. */
    public synchronized void actualizar(StockEvent event, int particion, long offset) {
        Estado anterior = stockPorSku.put(event.sku(), new Estado(event.nombre(), event.stockActual()));
        String alerta = evaluar(event, anterior);

        // La foto de sincronización (al arrancar la API) solo actualiza el estado: no llena el feed con un evento
        // por producto. Sí se informa si revela un problema (p. ej. un producto que ya estaba en 0).
        if (event.tipo() != StockEvent.StockTipo.SINCRONIZACION || alerta != null) {
            String detalle = event.sku() + " (" + event.nombre() + "): " + event.stockAnterior() + " -> "
                    + event.stockActual() + (alerta == null ? "" : " | " + alerta);
            eventosRecientes.registrar("STOCK_" + event.tipo(), detalle, event.timestamp(), particion, offset);
        }
    }

    /** Un tombstone (valor null) en el tópico compactado = el producto ya no existe: se deja de monitorear. */
    public synchronized void eliminar(String sku) {
        if (stockPorSku.remove(sku) != null) {
            log.info("Monitor de stock: {} eliminado (tombstone)", sku);
        }
    }

    /** Productos bajo el umbral, del menor stock al mayor (empate por sku). */
    public synchronized List<StockCritico> stockCritico() {
        return stockPorSku.entrySet().stream()
                .filter(e -> e.getValue().stock() < threshold)
                .map(e -> new StockCritico(e.getKey(), e.getValue().nombre(), e.getValue().stock()))
                .sorted(Comparator.comparingInt(StockCritico::stock).thenComparing(StockCritico::sku))
                .toList();
    }

    /** Stock conocido de un sku, o null si nunca llegó un evento suyo. */
    public synchronized Integer stockDe(String sku) {
        Estado estado = stockPorSku.get(sku);
        return estado == null ? null : estado.stock();
    }

    /** Devuelve el texto de la alerta si el stock cruzó un límite, o null. */
    private String evaluar(StockEvent event, Estado anterior) {
        int actual = event.stockActual();
        // Si el monitor no conocía el producto, se compara contra el stock anterior que trae el evento.
        int previo = anterior != null ? anterior.stock() : event.stockAnterior();
        boolean primeraVez = anterior == null;

        if (actual == 0 && (previo != 0 || primeraVez)) {
            log.warn("Monitor de stock: QUIEBRE DE STOCK en {} ({}): {} -> 0", event.sku(), event.nombre(), previo);
            return "QUIEBRE DE STOCK";
        }
        if (actual > 0 && actual < threshold && (previo >= threshold || primeraVez)) {
            log.warn("Monitor de stock: stock CRÍTICO en {} ({}): {} -> {} (umbral {})", event.sku(), event.nombre(),
                    previo, actual, threshold);
            return "stock crítico (< " + threshold + ")";
        }
        if (actual >= threshold && previo < threshold && !primeraVez) {
            log.info("Monitor de stock: {} ({}) se recuperó: {} -> {}", event.sku(), event.nombre(), previo, actual);
            return "stock recuperado";
        }
        return null;
    }
}
