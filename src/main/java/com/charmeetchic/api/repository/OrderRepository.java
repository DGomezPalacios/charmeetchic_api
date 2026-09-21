package com.charmeetchic.api.repository;

import com.charmeetchic.api.entity.Order;
import com.charmeetchic.api.enums.OrderStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a datos de órdenes. Las consultas que devuelven órdenes cargan también las líneas y sus
 * productos (EntityGraph) para evitar el problema N+1 al construir los DTOs.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Override
    @EntityGraph(attributePaths = {"items", "items.product"})
    Optional<Order> findById(Long id);

    @EntityGraph(attributePaths = {"items", "items.product"})
    List<Order> findByUserId(String userId, Sort sort);

    @EntityGraph(attributePaths = {"items", "items.product"})
    List<Order> findByStatus(OrderStatus status);

    /** Órdenes creadas en {@code [start, end)}, la más reciente primero. */
    @EntityGraph(attributePaths = {"items", "items.product"})
    @Query("""
            select o from Order o
            where o.createdAt >= :start and o.createdAt < :end
            order by o.createdAt desc
            """)
    List<Order> findByCreatedAtRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** Totales de ventas en {@code [start, end)}. Las sumas son null si no hay órdenes. */
    @Query("""
            select count(o) as orderCount,
                   sum(o.subtotal) as subtotal,
                   sum(o.tax) as tax,
                   sum(o.total) as total
            from Order o
            where o.createdAt >= :start and o.createdAt < :end
            """)
    SalesTotals summarizeSales(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** Proyección de {@link #summarizeSales}. */
    interface SalesTotals {
        Long getOrderCount();

        BigDecimal getSubtotal();

        BigDecimal getTax();

        BigDecimal getTotal();
    }
}
