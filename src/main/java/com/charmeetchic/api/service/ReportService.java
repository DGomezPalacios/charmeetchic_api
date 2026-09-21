package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.ReportDTO.CategorySales;
import com.charmeetchic.api.dto.ReportDTO.DateRange;
import com.charmeetchic.api.dto.ReportDTO.InventoryValue;
import com.charmeetchic.api.dto.ReportDTO.SalesSummary;
import com.charmeetchic.api.dto.ReportDTO.TopProduct;
import com.charmeetchic.api.repository.OrderItemRepository;
import com.charmeetchic.api.repository.OrderRepository;
import com.charmeetchic.api.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Reportes para ADMIN. Todos se calculan con consultas agregadas en la base de datos (no cargan
 * órdenes en memoria), así que escalan con el volumen de ventas. Una "venta" es cualquier orden
 * existente en el rango (las canceladas se eliminan, por lo que no cuentan).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;

    /** Ventas totales del periodo: cantidad de órdenes, subtotal, IVA, total y ticket promedio. */
    @Transactional(readOnly = true)
    public SalesSummary getTotalSales(DateRange range) {
        OrderRepository.SalesTotals totals = orderRepository.summarizeSales(range.start(), range.endExclusive());
        long orders = orZero(totals.getOrderCount());
        BigDecimal total = orZero(totals.getTotal());
        BigDecimal average = orders == 0
                ? BigDecimal.ZERO.setScale(2)
                : total.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);

        log.debug("Reporte de ventas {}: {} órdenes, total {}", range, orders, total);
        return new SalesSummary(range, orders, orZero(totals.getSubtotal()), orZero(totals.getTax()), total, average);
    }

    /** Unidades e ingresos (sin IVA) por categoría, de mayor a menor ingreso. */
    @Transactional(readOnly = true)
    public List<CategorySales> getSalesByCategory(DateRange range) {
        return orderItemRepository.salesByCategory(range.start(), range.endExclusive()).stream()
                .map(row -> new CategorySales(row.getCategory(), orZero(row.getUnits()), orZero(row.getRevenue())))
                .toList();
    }

    /** Valor del inventario activo a costo y a precio de venta, y la utilidad potencial. */
    @Transactional(readOnly = true)
    public InventoryValue getInventoryValue() {
        ProductRepository.InventoryTotals totals = productRepository.summarizeInventory();
        BigDecimal cost = orZero(totals.getCostValue());
        BigDecimal retail = orZero(totals.getRetailValue());
        return new InventoryValue(orZero(totals.getProductCount()), orZero(totals.getUnits()),
                cost, retail, retail.subtract(cost));
    }

    /** Los {@code limit} productos más vendidos (por unidades) del periodo. */
    @Transactional(readOnly = true)
    public List<TopProduct> getTopProducts(DateRange range, int limit) {
        return orderItemRepository.topProducts(range.start(), range.endExclusive(), Pageable.ofSize(limit)).stream()
                .map(row -> new TopProduct(row.getProductId(), row.getName(), row.getSku(),
                        orZero(row.getUnits()), orZero(row.getRevenue())))
                .toList();
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value;
    }
}
