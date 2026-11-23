package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.StreamingMetricsDTO.TopProducto;
import com.charmeetchic.api.streaming.config.StreamingProperties;
import com.charmeetchic.api.streaming.event.VentaEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Métricas de ventas EN TIEMPO REAL calculadas en memoria a partir del tópico {@code ventas.eventos}
 * (consumer group {@code metricas-ventas}).
 *
 * <ul>
 *   <li><b>Ventas del último minuto</b>: órdenes CREADAS con {@code timestamp} dentro de la ventana. Se usa la
 *       hora del evento, no la del consumo: si el consumidor estuvo pausado, al reanudar no cuenta como
 *       "recientes" ventas que ocurrieron hace rato.</li>
 *   <li><b>Ventas e ingresos totales</b> y <b>top de productos</b>: una CANCELADA resta lo que sumó su CREADA.</li>
 *   <li><b>Idempotencia</b>: Kafka entrega "al menos una vez" (tras un reintento o un rebalanceo un evento puede
 *       llegar de nuevo). Se recuerdan los últimos {@value #DEDUP_CAPACITY} {@code eventId} para no contar dos
 *       veces la misma venta.</li>
 * </ul>
 *
 * <p>Concurrencia: el grupo abre 3 consumidores (uno por partición) que llaman a {@link #registrar} a la vez;
 * los métodos son {@code synchronized} (secciones críticas de microsegundos con el volumen de la demo).
 *
 * <p>El estado vive en memoria: al reiniciar la API las métricas empiezan de cero (el log de Kafka permitiría
 * reconstruirlas releyendo desde el inicio, como hace el monitor de stock con su tópico compactado).
 */
@Slf4j
@Service
public class VentasMetricasService {

    static final int DEDUP_CAPACITY = 10_000;

    private final EventosRecientesService eventosRecientes;
    private final Clock clock;
    private final Duration window;
    private final int topSize;

    /** Timestamps de las órdenes creadas (más antigua primero); se poda lo que sale de la ventana. */
    private final Deque<Instant> creadasRecientes = new ArrayDeque<>();
    private final Map<String, ProductoVendido> productos = new HashMap<>();
    private final Map<String, Boolean> procesados = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > DEDUP_CAPACITY;
        }
    };
    private long ventasTotales;
    private BigDecimal ingresosTotales = BigDecimal.ZERO;

    @Autowired
    public VentasMetricasService(EventosRecientesService eventosRecientes, StreamingProperties props) {
        this(eventosRecientes, props, Clock.systemUTC());
    }

    VentasMetricasService(EventosRecientesService eventosRecientes, StreamingProperties props, Clock clock) {
        this.eventosRecientes = eventosRecientes;
        this.clock = clock;
        this.window = Duration.ofSeconds(props.metrics().windowSeconds());
        this.topSize = props.metrics().topSize();
    }

    /**
     * Incorpora una venta a las métricas.
     *
     * @return false si el evento ya se había procesado (duplicado) y se ignoró
     */
    public synchronized boolean registrar(VentaEvent event, int particion, long offset) {
        if (procesados.putIfAbsent(event.eventId(), Boolean.TRUE) != null) {
            log.info("Métricas: evento duplicado {} (orden {}) ignorado", event.eventId(), event.orderId());
            return false;
        }
        int signo = event.tipo() == VentaEvent.VentaTipo.CREADA ? 1 : -1;
        ventasTotales += signo;
        ingresosTotales = ingresosTotales.add(event.total().multiply(BigDecimal.valueOf(signo)));
        for (VentaEvent.Item item : event.items()) {
            productos.computeIfAbsent(item.sku(), sku -> new ProductoVendido())
                    .sumar(item.nombre(), (long) signo * item.cantidad());
        }
        if (signo > 0) {
            creadasRecientes.addLast(event.timestamp());
            podar();
        }
        eventosRecientes.registrar("VENTA_" + event.tipo(), describir(event), event.timestamp(), particion, offset);
        log.debug("Métricas: {} orden {} ({} u., {}) partición {} offset {}", event.tipo(), event.orderId(),
                event.unidades(), event.total(), particion, offset);
        return true;
    }

    /** Órdenes creadas con timestamp dentro de la ventana (por defecto, el último minuto). */
    public synchronized long ventasUltimoMinuto() {
        podar();
        Instant now = clock.instant();
        // Un evento con hora futura (relojes desfasados) no cuenta como "del último minuto".
        return creadasRecientes.stream().filter(t -> !t.isAfter(now)).count();
    }

    public synchronized long ventasTotales() {
        return ventasTotales;
    }

    public synchronized BigDecimal ingresosTotales() {
        return ingresosTotales;
    }

    /** Productos con más unidades netas vendidas (empate: por sku, para que el orden sea estable). */
    public synchronized List<TopProducto> topProductos() {
        return productos.entrySet().stream()
                .filter(e -> e.getValue().unidades > 0)
                .sorted(Comparator.comparingLong((Map.Entry<String, ProductoVendido> e) -> e.getValue().unidades)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(topSize)
                .map(e -> new TopProducto(e.getKey(), e.getValue().nombre, e.getValue().unidades))
                .toList();
    }

    /**
     * Saca los timestamps que quedaron fuera de la ventana. Los eventos pueden llegar desordenados entre
     * particiones, así que se recorre todo (el deque solo guarda el último minuto: es chico).
     */
    private void podar() {
        Instant limite = clock.instant().minus(window);
        creadasRecientes.removeIf(t -> t.isBefore(limite));
    }

    static String describir(VentaEvent event) {
        String total = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL")).format(event.total());
        String origen = event.origen() == VentaEvent.Origen.SIMULACION ? " [simulada]" : "";
        return "Orden " + event.orderId() + " " + event.tipo().name().toLowerCase(Locale.ROOT) + ": "
                + event.unidades() + " u., " + total + origen;
    }

    /** Acumulado de un producto: último nombre conocido y unidades netas. */
    private static final class ProductoVendido {
        private String nombre;
        private long unidades;

        void sumar(String nombre, long delta) {
            this.nombre = nombre;
            this.unidades += delta;
        }
    }
}
