package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.CartItemDTO;
import com.charmeetchic.api.dto.OrderDTO;
import com.charmeetchic.api.entity.Category;
import com.charmeetchic.api.entity.Order;
import com.charmeetchic.api.entity.OrderItem;
import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.enums.OrderStatus;
import com.charmeetchic.api.exception.ConflictException;
import com.charmeetchic.api.exception.InsufficientStockException;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.exception.ValidationException;
import com.charmeetchic.api.repository.OrderRepository;
import com.charmeetchic.api.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final String USER = "user-1";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;

    private OrderService service;
    private Category category;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, productRepository, new BigDecimal("0.19"));
        category = Category.builder().id(1L).name("Collares").build();
    }

    // ------------------------------------------------------------------ creación

    @Test
    void createOrder_calculatesTotals_mergesDuplicateLines_andDecrementsStock() {
        Product product = product(1L, "10000.00", 10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(100L);
            return o;
        });

        // dos líneas del mismo producto (2 + 1) se agrupan en una de 3 unidades
        OrderDTO result = service.createOrder(USER, " Av. Siempre Viva 742 ",
                List.of(new CartItemDTO(1L, 2), new CartItemDTO(1L, 1)));

        assertThat(result.id()).isEqualTo(100L);
        assertThat(result.userId()).isEqualTo(USER);
        assertThat(result.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.shippingAddress()).isEqualTo("Av. Siempre Viva 742");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).quantity()).isEqualTo(3);
        assertThat(result.subtotal()).isEqualByComparingTo("30000.00");
        assertThat(result.tax()).isEqualByComparingTo("5700.00");
        assertThat(result.total()).isEqualByComparingTo("35700.00");
        assertThat(product.getStock()).isEqualTo(7);
    }

    @Test
    void createOrder_snapshotsUnitPriceOnEachLine() {
        Product product = product(1L, "9990.50", 5);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderDTO result = service.createOrder(USER, "Dirección", List.of(new CartItemDTO(1L, 2)));

        assertThat(result.items().get(0).price()).isEqualByComparingTo("9990.50");
        assertThat(result.items().get(0).subtotal()).isEqualByComparingTo("19981.00");
    }

    @Test
    void createOrder_insufficientStock_throwsAndKeepsStock() {
        Product product = product(1L, "100.00", 2);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.createOrder(USER, "Dirección", List.of(new CartItemDTO(1L, 3))))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("disponible 2")
                .hasMessageContaining("solicitado 3");

        assertThat(product.getStock()).isEqualTo(2);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrder_unknownProduct_throwsNotFound() {
        when(productRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(USER, "Dirección", List.of(new CartItemDTO(9L, 1))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createOrder_inactiveProduct_isTreatedAsNotFound() {
        Product inactive = product(1L, "100.00", 5);
        inactive.setActive(false);
        when(productRepository.findById(1L)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.createOrder(USER, "Dirección", List.of(new CartItemDTO(1L, 1))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createOrder_emptyCart_throwsValidation() {
        assertThatThrownBy(() -> service.createOrder(USER, "Dirección", List.of()))
                .isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------ consulta y propiedad

    @Test
    void getOrderById_owner_canSeeIt() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L, USER, OrderStatus.PENDING, 1)));

        assertThat(service.getOrderById(1L, USER, false).id()).isEqualTo(1L);
    }

    @Test
    void getOrderById_otherUser_isDenied() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L, "someone-else", OrderStatus.PENDING, 1)));

        assertThatThrownBy(() -> service.getOrderById(1L, USER, false)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getOrderById_admin_canSeeAnyOrder() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L, "someone-else", OrderStatus.PENDING, 1)));

        assertThat(service.getOrderById(1L, "admin-1", true).userId()).isEqualTo("someone-else");
    }

    @Test
    void getOrdersByDateRange_invertedRange_throwsValidation() {
        LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> service.getOrdersByDateRange(now, now.minusDays(1)))
                .isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------ estados

    @Test
    void updateOrderStatus_nextStep_isAllowed() {
        Order order = order(1L, USER, OrderStatus.PENDING, 1);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderDTO result = service.updateOrderStatus(1L, OrderStatus.PROCESSING);

        assertThat(result.status()).isEqualTo(OrderStatus.PROCESSING);
    }

    @Test
    void updateOrderStatus_skippingSteps_throwsValidation() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L, USER, OrderStatus.PENDING, 1)));

        assertThatThrownBy(() -> service.updateOrderStatus(1L, OrderStatus.SENT))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("PROCESSING");
    }

    @Test
    void updateOrderStatus_goingBack_throwsValidation() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L, USER, OrderStatus.SENT, 1)));

        assertThatThrownBy(() -> service.updateOrderStatus(1L, OrderStatus.PENDING))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void updateOrderStatus_fromDelivered_throwsValidation() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L, USER, OrderStatus.DELIVERED, 1)));

        assertThatThrownBy(() -> service.updateOrderStatus(1L, OrderStatus.DELIVERED))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("entregada");
    }

    // ------------------------------------------------------------------ cancelación

    @Test
    void deleteOrder_pending_restoresStock() {
        Order order = order(1L, USER, OrderStatus.PENDING, 3); // 3 unidades de un producto con stock 7
        Product product = order.getItems().get(0).getProduct();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        service.deleteOrder(1L, USER, false);

        assertThat(product.getStock()).isEqualTo(10);
        verify(orderRepository).delete(order);
    }

    @Test
    void deleteOrder_alreadyProcessing_throwsConflict() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L, USER, OrderStatus.PROCESSING, 1)));

        assertThatThrownBy(() -> service.deleteOrder(1L, USER, false)).isInstanceOf(ConflictException.class);

        verify(orderRepository, never()).delete(any());
    }

    @Test
    void deleteOrder_otherUsersOrder_isDenied() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order(1L, "someone-else", OrderStatus.PENDING, 1)));

        assertThatThrownBy(() -> service.deleteOrder(1L, USER, false)).isInstanceOf(AccessDeniedException.class);

        verify(orderRepository, never()).delete(any());
    }

    // ------------------------------------------------------------------ helpers

    private Product product(Long id, String price, int stock) {
        return Product.builder()
                .id(id).name("Producto " + id).sku("SKU-" + id)
                .price(new BigDecimal(price)).cost(new BigDecimal("1.00"))
                .stock(stock).category(category).active(true).version(0L)
                .build();
    }

    /** Orden de una sola línea con {@code quantity} unidades de un producto que quedó con stock 7. */
    private Order order(Long id, String userId, OrderStatus status, int quantity) {
        Product product = product(1L, "1000.00", 7);
        Order order = Order.builder()
                .id(id).userId(userId).status(status).shippingAddress("Dirección")
                .subtotal(new BigDecimal("1000.00")).tax(new BigDecimal("190.00")).total(new BigDecimal("1190.00"))
                .build();
        order.addItem(OrderItem.builder().id(1L).product(product).quantity(quantity)
                .price(new BigDecimal("1000.00")).subtotal(new BigDecimal("1000.00")).build());
        return order;
    }
}
