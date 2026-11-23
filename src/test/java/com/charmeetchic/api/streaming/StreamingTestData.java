package com.charmeetchic.api.streaming;

import com.charmeetchic.api.streaming.config.StreamingProperties;
import com.charmeetchic.api.streaming.event.StockEvent;
import com.charmeetchic.api.streaming.event.VentaEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Datos de prueba compartidos por los tests unitarios del streaming. */
public final class StreamingTestData {

    private StreamingTestData() {
    }

    public static StreamingProperties props(boolean enabled, boolean simulatedFailures, int maxSimulated) {
        return new StreamingProperties(enabled,
                new StreamingProperties.Topics(
                        new StreamingProperties.TopicProperties("ventas.eventos", 3, (short) 3,
                                Map.of("cleanup.policy", "delete")),
                        new StreamingProperties.TopicProperties("inventario.stock", 3, (short) 3,
                                Map.of("cleanup.policy", "compact")),
                        new StreamingProperties.DeadLetterProperties(".DLT", 3, (short) 3, null)),
                new StreamingProperties.Consumers(
                        new StreamingProperties.GroupProperties("metricas-ventas", 3),
                        new StreamingProperties.GroupProperties("historial-ventas", 1),
                        new StreamingProperties.GroupProperties("monitor-stock", 1),
                        new StreamingProperties.GroupProperties("monitor-dlt", 1)),
                new StreamingProperties.Retry(2, 0),
                new StreamingProperties.Metrics(60, 5, 20),
                new StreamingProperties.Demo(simulatedFailures, maxSimulated));
    }

    public static StreamingProperties props() {
        return props(true, true, 1000);
    }

    public static VentaEvent venta(VentaEvent.VentaTipo tipo, String orderId, Instant ts, String total,
                                   VentaEvent.Item... items) {
        return new VentaEvent(UUID.randomUUID().toString(), tipo, ts, orderId, "u-1", List.of(items),
                new BigDecimal(total), VentaEvent.Origen.REAL);
    }

    public static VentaEvent.Item item(String sku, int cantidad) {
        return new VentaEvent.Item(sku, "Producto " + sku, cantidad, new BigDecimal("1000"));
    }

    public static StockEvent stock(StockEvent.StockTipo tipo, String sku, int antes, int despues) {
        return new StockEvent(UUID.randomUUID().toString(), tipo, Instant.now(), sku, 1L, "Producto " + sku,
                antes, despues, "test");
    }
}
