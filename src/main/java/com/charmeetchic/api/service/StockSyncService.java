package com.charmeetchic.api.service;

import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.repository.ProductRepository;
import com.charmeetchic.api.streaming.event.StockEvent;
import com.charmeetchic.api.streaming.producer.StreamingEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Al arrancar la API publica una "foto" del stock de cada producto activo en el tópico compactado
 * {@code inventario.stock} (tipo SINCRONIZACION).
 *
 * <p>Por qué: si Kafka estuvo caído mientras se vendía, esos eventos de stock se perdieron (se publican tras el
 * commit y no hay outbox). Republicar el stock actual deja el tópico otra vez igual a la BD; como es compactado,
 * no crece sin límite: solo queda el último valor de cada sku. También llena el monitor de stock desde el primer
 * momento (incluidos los productos que ya estaban bajo el umbral).
 *
 * <p>Se hace en otro hilo para no demorar el arranque si Kafka no responde.
 */
@Slf4j
@Service
public class StockSyncService {

    private final ProductRepository productRepository;
    private final StreamingEventPublisher publisher;

    public StockSyncService(ProductRepository productRepository, StreamingEventPublisher publisher) {
        this.productRepository = productRepository;
        this.publisher = publisher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (publisher.isEnabled()) {
            CompletableFuture.runAsync(this::sincronizar);
        }
    }

    /** Publica el stock actual de cada producto activo; devuelve cuántos eventos se enviaron. */
    public int sincronizar() {
        List<Product> productos = productRepository.findByActive(true);
        Instant now = Instant.now();
        productos.forEach(p -> publisher.publishStock(new StockEvent(UUID.randomUUID().toString(),
                StockEvent.StockTipo.SINCRONIZACION, now, p.getSku(), p.getId(), p.getName(), p.getStock(),
                p.getStock(), "sincronización al arrancar")));
        log.info("Kafka: stock de {} productos sincronizado en el tópico compactado", productos.size());
        return productos.size();
    }
}
