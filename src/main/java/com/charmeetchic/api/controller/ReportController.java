package com.charmeetchic.api.controller;

import com.charmeetchic.api.dto.ReportDTO.CategorySales;
import com.charmeetchic.api.dto.ReportDTO.DateRange;
import com.charmeetchic.api.dto.ReportDTO.InventoryValue;
import com.charmeetchic.api.dto.ReportDTO.SalesSummary;
import com.charmeetchic.api.dto.ReportDTO.TopProduct;
import com.charmeetchic.api.dto.ReportJobDTO;
import com.charmeetchic.api.dto.StockMovementDTO;
import com.charmeetchic.api.security.AuthenticatedUser;
import com.charmeetchic.api.service.AsyncReportService;
import com.charmeetchic.api.service.ReportService;
import com.charmeetchic.api.service.StockAuditService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Reportes de gestión. URL base: {@code /api/reports}. Todo el controlador es solo ADMIN.
 *
 * <p>Los reportes con periodo aceptan {@code from} y {@code to} (formato {@code yyyy-MM-dd}, ambos
 * inclusive). Si se omiten, se usan los últimos 30 días.
 */
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ReportController {

    private final ReportService reportService;
    private final AsyncReportService asyncReportService;
    private final StockAuditService stockAuditService;

    /** GET /api/reports/sales?from=2026-01-01&to=2026-01-31 */
    @GetMapping("/sales")
    public ResponseEntity<SalesSummary> getTotalSales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.getTotalSales(DateRange.of(from, to)));
    }

    /** GET /api/reports/sales-by-category?from=&to= */
    @GetMapping("/sales-by-category")
    public ResponseEntity<List<CategorySales>> getSalesByCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(reportService.getSalesByCategory(DateRange.of(from, to)));
    }

    /** GET /api/reports/inventory-value */
    @GetMapping("/inventory-value")
    public ResponseEntity<InventoryValue> getInventoryValue() {
        return ResponseEntity.ok(reportService.getInventoryValue());
    }

    /**
     * POST /api/reports/inventory/async — pide un reporte de inventario en segundo plano.
     * Responde 202 Accepted de inmediato con el id; el estado se consulta en {@code Location}.
     * Cuerpo opcional (solo demo): {@code {"simularError": "TRANSITORIO" | "TRANSITORIO_UNA_VEZ" | "INVALIDO"}}.
     */
    @PostMapping("/inventory/async")
    public ResponseEntity<ReportJobDTO> requestInventoryReport(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) ReportJobDTO.Request request) {
        ReportJobDTO job = asyncReportService.requestInventoryReport(user.userId(),
                request == null ? null : request.simularError());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/reports/async/{id}").buildAndExpand(job.id()).toUri();
        return ResponseEntity.accepted().location(location).body(job);
    }

    /** GET /api/reports/async/{id} — estado (PENDIENTE / COMPLETADO / FALLIDO) y resultado del reporte. */
    @GetMapping("/async/{id}")
    public ResponseEntity<ReportJobDTO> getAsyncReport(@PathVariable String id) {
        return ResponseEntity.ok(asyncReportService.getById(id));
    }

    /** GET /api/reports/stock-movements?productId=&page=&size= — auditoría escrita por el consumidor. */
    @GetMapping("/stock-movements")
    public ResponseEntity<Page<StockMovementDTO>> getStockMovements(
            @RequestParam(required = false) Long productId,
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(stockAuditService.getMovements(productId, pageable));
    }

    /** GET /api/reports/top-products?from=&to=&limit=10 */
    @GetMapping("/top-products")
    public ResponseEntity<List<TopProduct>> getTopProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "limit debe ser al menos 1")
            @Max(value = 50, message = "limit no puede superar 50") int limit) {
        return ResponseEntity.ok(reportService.getTopProducts(DateRange.of(from, to), limit));
    }
}
