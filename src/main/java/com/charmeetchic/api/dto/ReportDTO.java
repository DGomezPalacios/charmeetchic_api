package com.charmeetchic.api.dto;

import com.charmeetchic.api.exception.ValidationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Contenedor de los resultados de reportes. Cada reporte es un record anidado, de modo que
 * todo el modelo de reportes vive en un único archivo.
 */
public final class ReportDTO {

    private ReportDTO() {
    }

    /**
     * Rango de fechas de un reporte. Ambos extremos son INCLUSIVOS a nivel de día
     * ({@code to} incluye todo el día). Si faltan, se usan los últimos {@value #DEFAULT_DAYS} días.
     */
    public record DateRange(LocalDate from, LocalDate to) {

        public static final int DEFAULT_DAYS = 30;

        public DateRange {
            if (from == null || to == null) {
                throw new ValidationException("El rango de fechas requiere 'from' y 'to'");
            }
            if (from.isAfter(to)) {
                throw new ValidationException("'from' no puede ser posterior a 'to'");
            }
        }

        /** Construye el rango aplicando los valores por defecto cuando llegan nulos. */
        public static DateRange of(LocalDate from, LocalDate to) {
            LocalDate end = to != null ? to : LocalDate.now();
            LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS);
            return new DateRange(start, end);
        }

        /** Inicio del rango (inclusive): 00:00 del día {@code from}. */
        public LocalDateTime start() {
            return from.atStartOfDay();
        }

        /** Fin del rango (EXCLUSIVO): 00:00 del día siguiente a {@code to}. */
        public LocalDateTime endExclusive() {
            return to.plusDays(1).atStartOfDay();
        }
    }

    /** Ventas totales del periodo. Los importes son los congelados en cada orden. */
    public record SalesSummary(
            DateRange range,
            long orderCount,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal totalSales,
            BigDecimal averageOrderValue) {
    }

    /** Ventas de una categoría en el periodo (sobre subtotales de líneas, sin IVA). */
    public record CategorySales(
            String category,
            long unitsSold,
            BigDecimal revenue) {
    }

    /** Valor del inventario activo: a costo (lo invertido) y a precio de venta (lo potencial). */
    public record InventoryValue(
            long activeProducts,
            long totalUnits,
            BigDecimal costValue,
            BigDecimal retailValue,
            BigDecimal potentialProfit) {
    }

    /** Producto entre los más vendidos del periodo. */
    public record TopProduct(
            Long productId,
            String name,
            String sku,
            long unitsSold,
            BigDecimal revenue) {
    }
}
