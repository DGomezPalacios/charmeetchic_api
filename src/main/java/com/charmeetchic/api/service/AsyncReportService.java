package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.ProductDTO;
import com.charmeetchic.api.dto.ReportDTO.InventoryValue;
import com.charmeetchic.api.dto.ReportJobDTO;
import com.charmeetchic.api.entity.ReportJob;
import com.charmeetchic.api.enums.ReportStatus;
import com.charmeetchic.api.enums.SimulatedFailure;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.messaging.event.ReportRequestedEvent;
import com.charmeetchic.api.messaging.producer.ReportRequestPublisher;
import com.charmeetchic.api.repository.ReportJobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reportes de inventario generados en segundo plano.
 *
 * <ol>
 *   <li>{@link #requestInventoryReport}: el POST guarda un {@link ReportJob} PENDIENTE y publica un mensaje.
 *       La respuesta (202 Accepted) sale de inmediato, sin esperar el cálculo.</li>
 *   <li>{@link #generate}: el consumidor de {@code notificaciones.reportes} calcula el reporte con
 *       {@link ReportService} y {@link ProductService} (lógica existente) y lo deja COMPLETADO.</li>
 *   <li>{@link #getById}: el cliente consulta el estado con GET hasta verlo COMPLETADO o FALLIDO.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncReportService {

    public static final String INVENTORY_REPORT = "inventario";

    private final ReportJobRepository repository;
    private final ReportRequestPublisher publisher;
    private final ReportService reportService;
    private final ProductService productService;
    private final ObjectMapper objectMapper;

    /** Lo que guarda el reporte de inventario: valorización + productos bajo el umbral. */
    public record InventoryReport(Instant generatedAt, InventoryValue inventoryValue,
                                  List<LowStockItem> lowStockProducts) {
    }

    public record LowStockItem(Long productId, String sku, String name, Integer stock) {
    }

    /**
     * Crea el registro PENDIENTE y publica la solicitud. El mensaje sale tras el commit, así el consumidor
     * siempre encuentra el registro en la BD.
     */
    @Transactional
    public ReportJobDTO requestInventoryReport(String requestedBy, SimulatedFailure simulatedFailure) {
        Instant now = Instant.now();
        ReportJob job = repository.save(ReportJob.builder()
                .id(UUID.randomUUID().toString())
                .reportType(INVENTORY_REPORT)
                .status(ReportStatus.PENDIENTE)
                .requestedBy(requestedBy)
                .requestedAt(now)
                .build());
        publisher.publish(new ReportRequestedEvent(job.getId(), INVENTORY_REPORT, requestedBy, now, simulatedFailure));
        log.info("Reporte {} solicitado por {} (PENDIENTE){}", job.getId(), requestedBy,
                simulatedFailure == null ? "" : " con simularError=" + simulatedFailure);
        return ReportJobDTO.from(job);
    }

    /**
     * Calcula el reporte y lo marca COMPLETADO. Si ya estaba COMPLETADO (mensaje duplicado) no hace nada.
     *
     * @throws ResourceNotFoundException si el reporte no existe
     */
    @Transactional
    public void generate(String reportId) {
        ReportJob job = findOrThrow(reportId);
        if (job.getStatus() == ReportStatus.COMPLETADO) {
            log.info("Reporte {} ya estaba COMPLETADO: mensaje duplicado ignorado", reportId);
            return;
        }
        List<LowStockItem> lowStock = productService.getLowStockProducts().stream()
                .map(AsyncReportService::toLowStockItem)
                .toList();
        InventoryReport report = new InventoryReport(Instant.now(), reportService.getInventoryValue(), lowStock);

        job.setResult(toJson(report));
        job.setStatus(ReportStatus.COMPLETADO);
        job.setCompletedAt(Instant.now());
        job.setError(null);
        log.info("Reporte {} COMPLETADO: {} productos activos, {} con stock bajo", reportId,
                report.inventoryValue().activeProducts(), lowStock.size());
    }

    /** Lo llama el consumidor justo antes de mandar el mensaje a la DLQ. */
    @Transactional
    public void markFailed(String reportId, String reason) {
        repository.findById(reportId).ifPresent(job -> {
            job.setStatus(ReportStatus.FALLIDO);
            job.setCompletedAt(Instant.now());
            job.setError(truncate(reason));
            log.warn("Reporte {} marcado FALLIDO: {}", reportId, reason);
        });
    }

    @Transactional(readOnly = true)
    public ReportJobDTO getById(String reportId) {
        return ReportJobDTO.from(findOrThrow(reportId));
    }

    private ReportJob findOrThrow(String reportId) {
        return repository.findById(reportId).orElseThrow(() -> new ResourceNotFoundException("Reporte", reportId));
    }

    private String toJson(InventoryReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el reporte", e);
        }
    }

    private static LowStockItem toLowStockItem(ProductDTO p) {
        return new LowStockItem(p.id(), p.sku(), p.name(), p.stock());
    }

    private static String truncate(String text) {
        return text == null || text.length() <= ReportJob.MAX_ERROR_LENGTH
                ? text
                : text.substring(0, ReportJob.MAX_ERROR_LENGTH);
    }
}
