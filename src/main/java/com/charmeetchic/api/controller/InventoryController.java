package com.charmeetchic.api.controller;

import com.charmeetchic.api.dto.StockCheckDTO;
import com.charmeetchic.api.service.StockCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inventario. URL base: {@code /api/inventory}. Requiere usuario autenticado. */
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final StockCheckService stockCheckService;

    /**
     * GET /api/inventory/stock-check/{sku} — consulta SÍNCRONA por RabbitMQ (request-reply).
     * 200 con el stock · 404 si el SKU no existe · 504 si el consumidor no responde a tiempo · 503 sin RabbitMQ.
     */
    @GetMapping("/stock-check/{sku}")
    public ResponseEntity<StockCheckDTO> stockCheck(@PathVariable String sku) {
        return ResponseEntity.ok(stockCheckService.check(sku));
    }
}
