package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.StreamingMetricsDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Arma la respuesta de {@code GET /api/streaming/metrics} juntando lo que calculó cada consumidor: métricas de
 * ventas ({@code metricas-ventas}), stock crítico ({@code monitor-stock}) y el feed de últimos eventos.
 */
@Service
public class StreamingMetricsService {

    private final VentasMetricasService ventas;
    private final StockMonitorService stock;
    private final EventosRecientesService eventos;
    private final Clock clock;

    @Autowired
    public StreamingMetricsService(VentasMetricasService ventas, StockMonitorService stock,
                                   EventosRecientesService eventos) {
        this(ventas, stock, eventos, Clock.systemUTC());
    }

    StreamingMetricsService(VentasMetricasService ventas, StockMonitorService stock, EventosRecientesService eventos,
                            Clock clock) {
        this.ventas = ventas;
        this.stock = stock;
        this.eventos = eventos;
        this.clock = clock;
    }

    public StreamingMetricsDTO metricas() {
        return new StreamingMetricsDTO(clock.instant(), ventas.ventasUltimoMinuto(), ventas.ventasTotales(),
                ventas.ingresosTotales(), ventas.topProductos(), stock.stockCritico(), eventos.ultimos());
    }
}
