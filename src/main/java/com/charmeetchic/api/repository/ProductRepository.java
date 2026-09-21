package com.charmeetchic.api.repository;

import com.charmeetchic.api.entity.Category;
import com.charmeetchic.api.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/** Acceso a datos de productos. Las consultas derivadas se generan a partir del nombre del método. */
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCategory(Category category);

    List<Product> findByNameContainingIgnoreCase(String name);

    List<Product> findByStockLessThan(Integer stock);

    List<Product> findByActive(boolean active);

    /** Listado paginado del catálogo (el público solo ve activos). */
    @EntityGraph(attributePaths = "category")
    Page<Product> findByActive(boolean active, Pageable pageable);

    /** Productos activos por debajo del umbral, los más críticos primero. */
    @EntityGraph(attributePaths = "category")
    List<Product> findByActiveTrueAndStockLessThanOrderByStockAsc(Integer threshold);

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    boolean existsByCategoryId(Long categoryId);

    /**
     * Búsqueda pública: solo productos activos, por texto (nombre o descripción) y/o categoría.
     *
     * @param pattern    patrón LIKE ya en minúsculas y con comodines (ej. {@code %perla%}); nunca null
     * @param categoryId filtro opcional (null = todas las categorías)
     */
    @EntityGraph(attributePaths = "category")
    @Query("""
            select p from Product p
            where p.active = true
              and (:categoryId is null or p.category.id = :categoryId)
              and (lower(p.name) like :pattern or lower(coalesce(p.description, '')) like :pattern)
            """)
    Page<Product> search(@Param("pattern") String pattern,
                         @Param("categoryId") Long categoryId,
                         Pageable pageable);

    /** Totales del inventario activo. Las sumas son null si no hay productos. */
    @Query("""
            select count(p) as productCount,
                   sum(p.stock) as units,
                   sum(p.stock * p.cost) as costValue,
                   sum(p.stock * p.price) as retailValue
            from Product p
            where p.active = true
            """)
    InventoryTotals summarizeInventory();

    /** Proyección de {@link #summarizeInventory()}. */
    interface InventoryTotals {
        Long getProductCount();

        Long getUnits();

        BigDecimal getCostValue();

        BigDecimal getRetailValue();
    }
}
