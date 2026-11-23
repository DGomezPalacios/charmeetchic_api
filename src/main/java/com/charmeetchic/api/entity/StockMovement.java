package com.charmeetchic.api.entity;

import com.charmeetchic.api.enums.MovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Registro de auditoría de un movimiento de stock. Lo escribe el consumidor de la cola
 * {@code inventario.movimientos}, no los servicios de negocio: la venta no espera a la auditoría.
 *
 * <p>{@code eventId} es único: si RabbitMQ entrega dos veces el mismo mensaje, el segundo se descarta.
 * No hay relación JPA con {@link Product} a propósito: el registro histórico guarda una "foto" (sku, nombre)
 * y no se ve afectado si el producto cambia después.
 */
@Entity
@Table(name = "stock_movements", indexes = {
        @Index(name = "idx_stock_movements_product_id", columnList = "product_id"),
        @Index(name = "idx_stock_movements_occurred_at", columnList = "occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sku", nullable = false, length = 50)
    private String sku;

    @Column(name = "product_name", length = 150)
    private String productName;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private MovementType type;

    /** Variación con signo: negativa = salida, positiva = entrada. */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "previous_stock", nullable = false)
    private Integer previousStock;

    @Column(name = "new_stock", nullable = false)
    private Integer newStock;

    @Column(name = "reference", length = 200)
    private String reference;

    /** Cuándo ocurrió el movimiento (en el servicio de negocio). */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Cuándo lo registró el consumidor. La diferencia con occurredAt muestra la asincronía. */
    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
}
