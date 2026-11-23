package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.StreamingMetricsDTO;
import com.charmeetchic.api.streaming.event.StockEvent.StockTipo;
import com.charmeetchic.api.streaming.event.VentaEvent;
import com.charmeetchic.api.streaming.event.VentaEvent.VentaTipo;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.charmeetchic.api.streaming.StreamingTestData.item;
import static com.charmeetchic.api.streaming.StreamingTestData.props;
import static com.charmeetchic.api.streaming.StreamingTestData.stock;
import static com.charmeetchic.api.streaming.StreamingTestData.venta;
import static org.assertj.core.api.Assertions.assertThat;

/** Lógica de negocio de los consumidores de Kafka: métricas, monitor de stock, historial y feed. */
class StreamingConsumersServicesTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EventosRecientesService eventos = new EventosRecientesService(props());
    private final VentasMetricasService metricas = new VentasMetricasService(eventos, props(), clock);
    private final StockMonitorService monitor = new StockMonitorService(eventos, 5);

    // ------------------------------------------------------------------ métricas de ventas

    @Test
    void ventasCreadasSumanYCanceladasRestan() {
        metricas.registrar(venta(VentaTipo.CREADA, "1", NOW, "3000", item("A", 2), item("B", 1)), 0, 0);
        metricas.registrar(venta(VentaTipo.CREADA, "2", NOW, "1000", item("B", 1)), 1, 0);
        metricas.registrar(venta(VentaTipo.CANCELADA, "1", NOW, "3000", item("A", 2), item("B", 1)), 0, 1);

        assertThat(metricas.ventasTotales()).isEqualTo(1);
        assertThat(metricas.ingresosTotales()).isEqualByComparingTo("1000");
        // A quedó en 0 unidades netas: sale del ranking
        assertThat(metricas.topProductos()).extracting(StreamingMetricsDTO.TopProducto::sku).containsExactly("B");
        assertThat(eventos.ultimos().get(0).tipo()).isEqualTo("VENTA_CANCELADA");
    }

    @Test
    void eventoDuplicadoNoSeCuentaDosVeces() {
        VentaEvent v = venta(VentaTipo.CREADA, "1", NOW, "1000", item("A", 1));
        assertThat(metricas.registrar(v, 0, 0)).isTrue();
        assertThat(metricas.registrar(v, 0, 0)).isFalse();
        assertThat(metricas.ventasTotales()).isEqualTo(1);
    }

    @Test
    void ventasDelUltimoMinutoUsaLaHoraDelEvento() {
        metricas.registrar(venta(VentaTipo.CREADA, "1", NOW.minusSeconds(10), "1", item("A", 1)), 0, 0);
        metricas.registrar(venta(VentaTipo.CREADA, "2", NOW.minusSeconds(120), "1", item("A", 1)), 0, 1);
        metricas.registrar(venta(VentaTipo.CREADA, "3", NOW.plusSeconds(30), "1", item("A", 1)), 0, 2);

        assertThat(metricas.ventasUltimoMinuto()).isEqualTo(1);
        assertThat(metricas.ventasTotales()).isEqualTo(3);
    }

    @Test
    void topProductosOrdenaPorUnidadesYLimitaACinco() {
        String[] skus = {"A", "B", "C", "D", "E", "F"};
        for (int i = 0; i < skus.length; i++) {
            metricas.registrar(venta(VentaTipo.CREADA, "o" + i, NOW, "1", item(skus[i], i + 1)), 0, i);
        }
        metricas.registrar(venta(VentaTipo.CREADA, "x", NOW, "1", item("A", 1)), 0, 9); // empate A=2 con B=2

        assertThat(metricas.topProductos()).extracting(StreamingMetricsDTO.TopProducto::sku)
                .containsExactly("F", "E", "D", "C", "A");
    }

    @Test
    void describirMarcaLasVentasSimuladas() {
        VentaEvent sim = new VentaEvent("e", VentaTipo.CREADA, NOW, "SIM-1", "s", java.util.List.of(item("A", 2)),
                new java.math.BigDecimal("1990"), VentaEvent.Origen.SIMULACION);
        assertThat(VentasMetricasService.describir(sim)).startsWith("Orden SIM-1 creada: 2 u.").endsWith("[simulada]");
    }

    // ------------------------------------------------------------------ monitor de stock

    @Test
    void detectaQuiebreStockCriticoYRecuperacion() {
        monitor.actualizar(stock(StockTipo.VENTA, "S1", 10, 8), 0, 0);   // normal: sin alerta
        monitor.actualizar(stock(StockTipo.VENTA, "S1", 8, 3), 0, 1);    // cruza el umbral
        assertThat(eventos.ultimos().get(0).detalle()).contains("stock crítico");
        monitor.actualizar(stock(StockTipo.VENTA, "S1", 3, 2), 0, 2);    // ya era crítico: no repite
        assertThat(eventos.ultimos().get(0).detalle()).doesNotContain("|");
        monitor.actualizar(stock(StockTipo.VENTA, "S1", 2, 0), 0, 3);    // quiebre
        assertThat(eventos.ultimos().get(0).detalle()).contains("QUIEBRE DE STOCK");
        assertThat(monitor.stockCritico()).singleElement().satisfies(s -> assertThat(s.stock()).isZero());
        monitor.actualizar(stock(StockTipo.CANCELACION, "S1", 0, 9), 0, 4); // recupera
        assertThat(eventos.ultimos().get(0).detalle()).contains("stock recuperado");
        assertThat(monitor.stockCritico()).isEmpty();
        assertThat(monitor.stockDe("S1")).isEqualTo(9);
    }

    @Test
    void sincronizacionSoloApareceEnElFeedSiRevelaUnProblema() {
        monitor.actualizar(stock(StockTipo.SINCRONIZACION, "OK", 20, 20), 0, 0);
        assertThat(eventos.ultimos()).isEmpty();
        monitor.actualizar(stock(StockTipo.SINCRONIZACION, "BAJO", 2, 2), 0, 1);
        monitor.actualizar(stock(StockTipo.SINCRONIZACION, "CERO", 0, 0), 0, 2);
        assertThat(eventos.ultimos()).hasSize(2);
        assertThat(monitor.stockCritico()).extracting(StreamingMetricsDTO.StockCritico::sku)
                .containsExactly("CERO", "BAJO");
    }

    @Test
    void tombstoneEliminaElProductoDelMonitor() {
        monitor.actualizar(stock(StockTipo.VENTA, "S1", 3, 2), 0, 0);
        monitor.eliminar("S1");
        monitor.eliminar("NO-EXISTE");
        assertThat(monitor.stockDe("S1")).isNull();
        assertThat(monitor.stockCritico()).isEmpty();
    }

    // ------------------------------------------------------------------ historial, feed y respuesta

    @Test
    void historialCuentaPorParticionYGuardaLasUltimas() {
        HistorialVentasService historial = new HistorialVentasService();
        for (int i = 0; i < HistorialVentasService.CAPACITY + 5; i++) {
            historial.registrar(venta(VentaTipo.CREADA, "o" + i, NOW, "1", item("A", 1)), i % 3, i);
        }
        HistorialVentasService.Resumen r = historial.resumen("historial-ventas");
        assertThat(r.procesados()).isEqualTo(55);
        assertThat(r.porParticion()).containsKeys(0, 1, 2);
        assertThat(r.ultimas()).hasSize(HistorialVentasService.CAPACITY);
        assertThat(r.ultimas().get(0).orderId()).isEqualTo("o54");
    }

    @Test
    void feedGuardaSoloLosUltimosNEventos() {
        for (int i = 0; i < 25; i++) {
            eventos.registrar("T", "d" + i, NOW, 0, i);
        }
        assertThat(eventos.ultimos()).hasSize(20);
        assertThat(eventos.ultimos().get(0).offset()).isEqualTo(24);
    }

    @Test
    void metricasJuntaLoDeCadaConsumidor() {
        metricas.registrar(venta(VentaTipo.CREADA, "1", NOW, "5000", item("A", 1)), 0, 0);
        monitor.actualizar(stock(StockTipo.VENTA, "S1", 6, 1), 1, 0);

        StreamingMetricsDTO dto = new StreamingMetricsService(metricas, monitor, eventos, clock).metricas();

        assertThat(dto.generadoEn()).isEqualTo(NOW);
        assertThat(dto.ventasUltimoMinuto()).isEqualTo(1);
        assertThat(dto.ventasTotales()).isEqualTo(1);
        assertThat(dto.ingresosTotales()).isEqualByComparingTo("5000");
        assertThat(dto.topProductos()).hasSize(1);
        assertThat(dto.stockCritico()).hasSize(1);
        assertThat(dto.ultimosEventos()).hasSize(2);
    }
}
