package com.charmeetchic.api.controller;

import com.charmeetchic.api.dto.ReportDTO.CategorySales;
import com.charmeetchic.api.dto.ReportDTO.DateRange;
import com.charmeetchic.api.dto.ReportDTO.InventoryValue;
import com.charmeetchic.api.dto.ReportDTO.SalesSummary;
import com.charmeetchic.api.dto.ReportDTO.TopProduct;
import com.charmeetchic.api.service.ReportService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
