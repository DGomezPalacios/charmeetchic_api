package com.charmeetchic.api.controller;

import com.charmeetchic.api.dto.StreamingMetricsDTO;
import com.charmeetchic.api.enums.TipoDefecto;
import com.charmeetchic.api.service.HistorialVentasService;
import com.charmeetchic.api.service.StreamingMetricsService;
import com.charmeetchic.api.service.StreamingSimulationService;
import com.charmeetchic.api.streaming.config.StreamingProperties;
import com.charmeetchic.api.streaming.consumer.StreamingListenerControlService;
import com.charmeetchic.api.streaming.consumer.StreamingListenerControlService.ListenerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Streaming con Kafka (EP5). URL base: {@code /api/streaming}. Solo ADMIN.
 *
 * <pre>
 *   GET  /streaming/metrics                    métricas en tiempo real (contrato del dashboard)
 *   GET  /streaming/historial                  lo que vio el 2.º grupo (historial-ventas), por partición
 *   POST /streaming/simulate?ventas=N[&defectuoso=true&tipoDefecto=VALIDACION|JSON|TRANSITORIO]
 *   GET  /streaming/consumers                  listeners, grupos y particiones asignadas
 *   POST /streaming/consumers/{id}/pause       deja de consumir (el lag crece)
 *   POST /streaming/consumers/{id}/resume      vuelve a consumir (el lag baja)
 * </pre>
 */
@RestController
@RequestMapping("/streaming")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class StreamingController {

    private final StreamingMetricsService metricsService;
    private final HistorialVentasService historialService;
    private final StreamingSimulationService simulationService;
    private final StreamingListenerControlService listenerControl;
    private final StreamingProperties props;

    /** GET /api/streaming/metrics */
    @GetMapping("/metrics")
    public ResponseEntity<StreamingMetricsDTO> metrics() {
        return ResponseEntity.ok(metricsService.metricas());
    }

    /** GET /api/streaming/historial */
    @GetMapping("/historial")
    public ResponseEntity<HistorialVentasService.Resumen> historial() {
        return ResponseEntity.ok(historialService.resumen(props.consumers().historialVentas().group()));
    }

    /** POST /api/streaming/simulate?ventas=N — 202: los eventos se publicaron; se consumen en segundo plano. */
    @PostMapping("/simulate")
    public ResponseEntity<StreamingSimulationService.Resultado> simulate(
            @RequestParam(defaultValue = "10") int ventas,
            @RequestParam(defaultValue = "false") boolean defectuoso,
            @RequestParam(defaultValue = "VALIDACION") TipoDefecto tipoDefecto) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(simulationService.simular(ventas, defectuoso, tipoDefecto));
    }

    /** GET /api/streaming/consumers */
    @GetMapping("/consumers")
    public ResponseEntity<List<ListenerStatus>> consumers() {
        return ResponseEntity.ok(listenerControl.list());
    }

    /** POST /api/streaming/consumers/{id}/pause */
    @PostMapping("/consumers/{id}/pause")
    public ResponseEntity<ListenerStatus> pause(@PathVariable String id) {
        return ResponseEntity.ok(listenerControl.pause(id));
    }

    /** POST /api/streaming/consumers/{id}/resume */
    @PostMapping("/consumers/{id}/resume")
    public ResponseEntity<ListenerStatus> resume(@PathVariable String id) {
        return ResponseEntity.ok(listenerControl.resume(id));
    }
}
