package com.charmeetchic.api.service;

import com.charmeetchic.api.messaging.event.StockQueryReply;
import com.charmeetchic.api.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/** Lado que RESPONDE la consulta síncrona de stock: lo llama el consumidor RPC. */
@Service
public class StockLookupService {

    private final ProductRepository productRepository;
    private final int lowStockThreshold;

    public StockLookupService(ProductRepository productRepository,
                              @Value("${app.inventory.low-stock-threshold:5}") int lowStockThreshold) {
        this.productRepository = productRepository;
        this.lowStockThreshold = lowStockThreshold;
    }

    /** Stock actual de un producto activo; {@code found=false} si no existe. */
    @Transactional(readOnly = true)
    public StockQueryReply lookup(String sku, String respondedBy) {
        String normalized = sku.trim().toUpperCase(Locale.ROOT);
        return productRepository.findBySkuAndActiveTrue(normalized)
                .map(p -> new StockQueryReply(p.getSku(), true, p.getId(), p.getName(), p.getStock(),
                        p.getStock() < lowStockThreshold, lowStockThreshold, Instant.now(), respondedBy))
                .orElseGet(() -> StockQueryReply.notFound(normalized, respondedBy));
    }
}
