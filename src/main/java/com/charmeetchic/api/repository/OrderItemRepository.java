package com.charmeetchic.api.repository;

import com.charmeetchic.api.entity.OrderItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Consultas agregadas sobre líneas de orden, base de los reportes. Rango de fechas: {@code [start, end)}. */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    @Query("""
            select c.name as category,
                   sum(oi.quantity) as units,
                   sum(oi.subtotal) as revenue
            from OrderItem oi
              join oi.order o
              join oi.product p
              join p.category c
            where o.createdAt >= :start and o.createdAt < :end
            group by c.name
            order by sum(oi.subtotal) desc
            """)
    List<CategorySalesRow> salesByCategory(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end);

    /** Productos más vendidos por unidades (desempata por ingresos). Limitar con {@code Pageable.ofSize(n)}. */
    @Query("""
            select p.id as productId,
                   p.name as name,
                   p.sku as sku,
                   sum(oi.quantity) as units,
                   sum(oi.subtotal) as revenue
            from OrderItem oi
              join oi.order o
              join oi.product p
            where o.createdAt >= :start and o.createdAt < :end
            group by p.id, p.name, p.sku
            order by sum(oi.quantity) desc, sum(oi.subtotal) desc
            """)
    List<TopProductRow> topProducts(@Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end,
                                    Pageable pageable);

    /** Proyección de {@link #salesByCategory}. */
    interface CategorySalesRow {
        String getCategory();

        Long getUnits();

        BigDecimal getRevenue();
    }

    /** Proyección de {@link #topProducts}. */
    interface TopProductRow {
        Long getProductId();

        String getName();

        String getSku();

        Long getUnits();

        BigDecimal getRevenue();
    }
}
