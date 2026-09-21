package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.ReportDTO.CategorySales;
import com.charmeetchic.api.dto.ReportDTO.DateRange;
import com.charmeetchic.api.dto.ReportDTO.InventoryValue;
import com.charmeetchic.api.dto.ReportDTO.SalesSummary;
import com.charmeetchic.api.dto.ReportDTO.TopProduct;
import com.charmeetchic.api.exception.ValidationException;
import com.charmeetchic.api.repository.OrderItemRepository;
import com.charmeetchic.api.repository.OrderRepository;
import com.charmeetchic.api.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final DateRange JANUARY = new DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END_EXCLUSIVE = LocalDateTime.of(2026, 2, 1, 0, 0);

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ProductRepository productRepository;
    @InjectMocks
    private ReportService service;

    // ------------------------------------------------------------------ DateRange

    @Test
    void dateRange_toIsInclusive_endIsMidnightOfNextDay() {
        assertThat(JANUARY.start()).isEqualTo(START);
        assertThat(JANUARY.endExclusive()).isEqualTo(END_EXCLUSIVE);
    }

    @Test
    void dateRange_invertedDates_throwsValidation() {
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void dateRange_of_defaultsToLast30Days() {
        DateRange range = DateRange.of(null, null);

        assertThat(range.to()).isEqualTo(LocalDate.now());
        assertThat(range.from()).isEqualTo(LocalDate.now().minusDays(30));
    }

    // ------------------------------------------------------------------ ventas

    @Test
    void getTotalSales_computesAverageOrderValue() {
        OrderRepository.SalesTotals totals = mock(OrderRepository.SalesTotals.class);
        when(totals.getOrderCount()).thenReturn(3L);
        when(totals.getSubtotal()).thenReturn(new BigDecimal("100000.00"));
        when(totals.getTax()).thenReturn(new BigDecimal("19000.00"));
        when(totals.getTotal()).thenReturn(new BigDecimal("119000.00"));
        when(orderRepository.summarizeSales(START, END_EXCLUSIVE)).thenReturn(totals);

        SalesSummary summary = service.getTotalSales(JANUARY);

        assertThat(summary.orderCount()).isEqualTo(3);
        assertThat(summary.totalSales()).isEqualByComparingTo("119000.00");
        assertThat(summary.averageOrderValue()).isEqualByComparingTo("39666.67");
    }

    @Test
    void getTotalSales_noOrders_returnsZeros() {
        OrderRepository.SalesTotals totals = mock(OrderRepository.SalesTotals.class);
        when(totals.getOrderCount()).thenReturn(0L);
        when(orderRepository.summarizeSales(START, END_EXCLUSIVE)).thenReturn(totals);

        SalesSummary summary = service.getTotalSales(JANUARY);

        assertThat(summary.orderCount()).isZero();
        assertThat(summary.totalSales()).isEqualByComparingTo("0");
        assertThat(summary.averageOrderValue()).isEqualByComparingTo("0");
    }

    @Test
    void getSalesByCategory_mapsRows() {
        OrderItemRepository.CategorySalesRow row = mock(OrderItemRepository.CategorySalesRow.class);
        when(row.getCategory()).thenReturn("Collares");
        when(row.getUnits()).thenReturn(12L);
        when(row.getRevenue()).thenReturn(new BigDecimal("250000.00"));
        when(orderItemRepository.salesByCategory(START, END_EXCLUSIVE)).thenReturn(List.of(row));

        List<CategorySales> result = service.getSalesByCategory(JANUARY);

        assertThat(result).containsExactly(new CategorySales("Collares", 12, new BigDecimal("250000.00")));
    }

    @Test
    void getTopProducts_passesLimitAsPageSize() {
        OrderItemRepository.TopProductRow row = mock(OrderItemRepository.TopProductRow.class);
        when(row.getProductId()).thenReturn(4L);
        when(row.getName()).thenReturn("Anillo");
        when(row.getSku()).thenReturn("ANI-1");
        when(row.getUnits()).thenReturn(9L);
        when(row.getRevenue()).thenReturn(new BigDecimal("90000.00"));
        when(orderItemRepository.topProducts(START, END_EXCLUSIVE, Pageable.ofSize(5))).thenReturn(List.of(row));

        List<TopProduct> result = service.getTopProducts(JANUARY, 5);

        assertThat(result).extracting(TopProduct::sku).containsExactly("ANI-1");
        assertThat(result.get(0).unitsSold()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ inventario

    @Test
    void getInventoryValue_computesPotentialProfit() {
        ProductRepository.InventoryTotals totals = mock(ProductRepository.InventoryTotals.class);
        when(totals.getProductCount()).thenReturn(14L);
        when(totals.getUnits()).thenReturn(300L);
        when(totals.getCostValue()).thenReturn(new BigDecimal("1000000.00"));
        when(totals.getRetailValue()).thenReturn(new BigDecimal("2500000.00"));
        when(productRepository.summarizeInventory()).thenReturn(totals);

        InventoryValue value = service.getInventoryValue();

        assertThat(value.activeProducts()).isEqualTo(14);
        assertThat(value.totalUnits()).isEqualTo(300);
        assertThat(value.potentialProfit()).isEqualByComparingTo("1500000.00");
    }

    @Test
    void getInventoryValue_emptyInventory_returnsZeros() {
        ProductRepository.InventoryTotals totals = mock(ProductRepository.InventoryTotals.class);
        when(totals.getProductCount()).thenReturn(0L);
        when(productRepository.summarizeInventory()).thenReturn(totals);

        InventoryValue value = service.getInventoryValue();

        assertThat(value.costValue()).isEqualByComparingTo("0");
        assertThat(value.retailValue()).isEqualByComparingTo("0");
        assertThat(value.potentialProfit()).isEqualByComparingTo("0");
    }
}
