package com.charmeetchic.api.service;

import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.enums.TipoDefecto;
import com.charmeetchic.api.exception.ConflictException;
import com.charmeetchic.api.exception.MessagingUnavailableException;
import com.charmeetchic.api.exception.ValidationException;
import com.charmeetchic.api.repository.ProductRepository;
import com.charmeetchic.api.streaming.config.StreamingProperties;
import com.charmeetchic.api.streaming.event.VentaEvent;
import com.charmeetchic.api.streaming.producer.StreamingEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Genera ventas SINTÉTICAS para la demo ({@code POST /api/streaming/simulate}): mueve el dashboard y, con un
 * consumidor pausado, deja ver cómo sube el lag.
 *
 * <p>Las ventas son <b>válidas</b> (productos reales del catálogo, precios reales, total con IVA) pero se
 * publican <b>solo en Kafka</b>, con {@code origen=SIMULACION} y órdenes {@code SIM-...}: no crean órdenes ni
 * descuentan stock en la BD, y por lo tanto tampoco publican eventos de stock (inventar un stock en el tópico
 * compactado lo dejaría distinto de la BD). Para ver el stock moverse se usa una compra real
 * ({@code POST /api/orders}).
 *
 * <p>Con {@code defectuoso=true} publica además UN evento defectuoso del tipo pedido, para mostrar el .DLT.
 */
@Slf4j
@Service
public class StreamingSimulationService {

    static final String SIMULATED_USER = "simulador";

    private final ProductRepository productRepository;
    private final StreamingEventPublisher publisher;
    private final StreamingProperties props;
    private final BigDecimal taxRate;
    private final Random random;

    /** Resultado de una simulación. */
    public record Resultado(int ventasPublicadas, boolean defectuoso, TipoDefecto tipoDefecto, String topico) {
    }

    @Autowired
    public StreamingSimulationService(ProductRepository productRepository, StreamingEventPublisher publisher,
                                      StreamingProperties props, @Value("${app.order.tax-rate:0.19}") BigDecimal taxRate) {
        this(productRepository, publisher, props, taxRate, new Random());
    }

    StreamingSimulationService(ProductRepository productRepository, StreamingEventPublisher publisher,
                               StreamingProperties props, BigDecimal taxRate, Random random) {
        this.productRepository = productRepository;
        this.publisher = publisher;
        this.props = props;
        this.taxRate = taxRate;
        this.random = random;
    }

    /**
     * @param ventas      cantidad de ventas sintéticas (0 permite publicar solo el defectuoso)
     * @param defectuoso  si además se publica un evento defectuoso
     * @param tipoDefecto qué clase de defecto (si {@code defectuoso})
     */
    @Transactional(readOnly = true)
    public Resultado simular(int ventas, boolean defectuoso, TipoDefecto tipoDefecto) {
        if (!publisher.isEnabled()) {
            throw new MessagingUnavailableException(
                    "Kafka está deshabilitado en este despliegue (STREAMING_ENABLED=false)", null);
        }
        int max = props.demo().maxSimulatedSales();
        if (ventas < 0 || ventas > max) {
            throw new ValidationException("'ventas' debe estar entre 0 y " + max);
        }
        if (ventas == 0 && !defectuoso) {
            throw new ValidationException("Nada que simular: pide al menos 1 venta o defectuoso=true");
        }
        if (defectuoso && tipoDefecto == TipoDefecto.TRANSITORIO && !props.demo().simulatedFailuresEnabled()) {
            throw new ValidationException("La simulación de fallos transitorios está deshabilitada "
                    + "(STREAMING_SIMULATED_FAILURES=false)");
        }

        List<Product> catalogo = productRepository.findByActive(true);
        if (ventas > 0 && catalogo.isEmpty()) {
            throw new ConflictException("No hay productos activos con que simular ventas");
        }
        for (int i = 0; i < ventas; i++) {
            publisher.publishVenta(ventaSintetica(catalogo));
        }
        if (defectuoso) {
            publicarDefectuoso(tipoDefecto, catalogo);
        }
        log.info("Simulación: {} ventas sintéticas publicadas{}", ventas,
                defectuoso ? " + 1 evento defectuoso (" + tipoDefecto + ")" : "");
        return new Resultado(ventas, defectuoso, defectuoso ? tipoDefecto : null, props.topics().ventas().name());
    }

    /** Venta válida: 1 a 3 productos distintos del catálogo, 1 a 3 unidades de cada uno, total con IVA. */
    VentaEvent ventaSintetica(List<Product> catalogo) {
        List<Product> mezclados = new ArrayList<>(catalogo);
        Collections.shuffle(mezclados, random);
        int lineas = 1 + random.nextInt(Math.min(3, mezclados.size()));
        List<VentaEvent.Item> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (Product p : mezclados.subList(0, lineas)) {
            int cantidad = 1 + random.nextInt(3);
            items.add(new VentaEvent.Item(p.getSku(), p.getName(), cantidad, p.getPrice()));
            subtotal = subtotal.add(p.getPrice().multiply(BigDecimal.valueOf(cantidad)));
        }
        BigDecimal total = subtotal.add(subtotal.multiply(taxRate)).setScale(2, RoundingMode.HALF_UP);
        return new VentaEvent(UUID.randomUUID().toString(), VentaEvent.VentaTipo.CREADA, Instant.now(), nuevaOrden(),
                SIMULATED_USER, items, total, VentaEvent.Origen.SIMULACION);
    }

    private void publicarDefectuoso(TipoDefecto tipo, List<Product> catalogo) {
        String orderId = nuevaOrden();
        switch (tipo) {
            // Sin ítems y con total negativo: JSON bien formado pero que no pasa la validación del consumidor.
            case VALIDACION -> publisher.publishInvalidVentaForDemo(new VentaEvent(UUID.randomUUID().toString(),
                    VentaEvent.VentaTipo.CREADA, Instant.now(), orderId, SIMULATED_USER, List.of(),
                    new BigDecimal("-1000"), VentaEvent.Origen.SIMULACION));
            // Ni siquiera es JSON.
            case JSON -> publisher.publishRawForDemo(orderId, "{\"eventId\": \"roto\", esto no es JSON");
            // Venta válida que el consumidor de métricas no podrá procesar (reintentos y luego .DLT).
            case TRANSITORIO -> {
                if (catalogo.isEmpty()) {
                    throw new ConflictException("No hay productos activos con que simular la venta");
                }
                publisher.publishVentaWithSimulatedFailureForDemo(ventaSintetica(catalogo));
            }
        }
    }

    private static String nuevaOrden() {
        return "SIM-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
