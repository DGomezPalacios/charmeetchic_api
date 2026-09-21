package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.CartItemDTO;
import com.charmeetchic.api.dto.OrderDTO;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lógica de órdenes de compra.
 *
 * <p>Reglas principales:
 * <ul>
 *   <li>Al crear una orden se descuenta el stock en la MISMA transacción. Si dos clientes compran la
 *       última unidad a la vez, el bloqueo optimista de {@link Product} hace fallar a uno (HTTP 409).</li>
 *   <li>El precio lo fija el servidor y se congela en cada línea. Los precios son netos:
 *       {@code tax = subtotal * tasa} y {@code total = subtotal + tax}.</li>
 *   <li>El estado solo avanza de a un paso: PENDING -> PROCESSING -> SENT -> DELIVERED.</li>
 *   <li>El cliente solo ve y cancela SUS órdenes (un ADMIN puede ver/cancelar cualquiera). Cancelar
 *       solo es posible en PENDING y devuelve el stock.</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderService {

    private static final int MONEY_SCALE = 2;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final BigDecimal taxRate;

    public OrderService(OrderRepository orderRepository,
                        ProductRepository productRepository,
                        @Value("${app.order.tax-rate:0.19}") BigDecimal taxRate) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.taxRate = taxRate;
    }

    /**
     * Crea una orden PENDING para {@code userId} y descuenta el stock. Líneas repetidas del mismo
     * producto se suman.
     *
     * @throws ResourceNotFoundException  si un producto no existe o está inactivo
     * @throws InsufficientStockException si no alcanza el stock de algún producto
     */
    @Transactional
    public OrderDTO createOrder(String userId, String shippingAddress, List<CartItemDTO> items) {
        if (items == null || items.isEmpty()) {
            throw new ValidationException("La orden debe tener al menos un producto");
        }

        Map<Long, Integer> quantities = new LinkedHashMap<>();
        items.forEach(i -> quantities.merge(i.productId(), i.quantity(), Integer::sum));

        Order order = Order.builder()
                .userId(userId)
                .status(OrderStatus.PENDING)
                .shippingAddress(shippingAddress.trim())
                .build();

        BigDecimal subtotal = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> line : quantities.entrySet()) {
            int quantity = line.getValue();
            Product product = productRepository.findById(line.getKey())
                    .filter(Product::isActive)
                    .orElseThrow(() -> new ResourceNotFoundException("Producto", line.getKey()));

            if (product.getStock() < quantity) {
                throw new InsufficientStockException(product.getName(), product.getStock(), quantity);
            }
            product.setStock(product.getStock() - quantity);

            BigDecimal lineSubtotal = money(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
            order.addItem(OrderItem.builder()
                    .product(product)
                    .quantity(quantity)
                    .price(product.getPrice())
                    .subtotal(lineSubtotal)
                    .build());
            subtotal = subtotal.add(lineSubtotal);
        }

        BigDecimal tax = money(subtotal.multiply(taxRate));
        order.setSubtotal(subtotal);
        order.setTax(tax);
        order.setTotal(subtotal.add(tax));

        Order saved = orderRepository.save(order);
        log.info("Orden creada: id={}, userId={}, líneas={}, total={}", saved.getId(), userId,
                saved.getItems().size(), saved.getTotal());
        return OrderDTO.from(saved);
    }

    /** Órdenes del usuario, la más reciente primero. */
    @Transactional(readOnly = true)
    public List<OrderDTO> getOrdersByUser(String userId) {
        return orderRepository.findByUserId(userId, Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream().map(OrderDTO::from).toList();
    }

    /** Detalle de una orden. Un usuario corriente solo puede ver las suyas. */
    @Transactional(readOnly = true)
    public OrderDTO getOrderById(Long id, String requesterId, boolean requesterIsAdmin) {
        return OrderDTO.from(findOwnedOrThrow(id, requesterId, requesterIsAdmin));
    }

    /** Solo ADMIN. Avanza la orden al siguiente estado del ciclo de vida. */
    @Transactional
    public OrderDTO updateOrderStatus(Long id, OrderStatus newStatus) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Orden", id));
        OrderStatus current = order.getStatus();

        if (newStatus.ordinal() != current.ordinal() + 1) {
            String allowed = current == OrderStatus.DELIVERED
                    ? "la orden ya fue entregada y no admite más cambios"
                    : "el único estado permitido es " + OrderStatus.values()[current.ordinal() + 1];
            throw new ValidationException("Transición inválida " + current + " -> " + newStatus + ": " + allowed);
        }
        order.setStatus(newStatus);
        Order saved = orderRepository.saveAndFlush(order); // flush: la respuesta trae updatedAt actualizado
        log.info("Orden {} cambió de estado: {} -> {}", id, current, newStatus);
        return OrderDTO.from(saved);
    }

    /** Cancela (elimina) una orden PENDING y devuelve el stock a los productos. */
    @Transactional
    public void deleteOrder(Long id, String requesterId, boolean requesterIsAdmin) {
        Order order = findOwnedOrThrow(id, requesterId, requesterIsAdmin);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ConflictException("Solo se pueden cancelar órdenes PENDING; esta orden está " + order.getStatus());
        }
        order.getItems().forEach(item -> {
            Product product = item.getProduct();
            product.setStock(product.getStock() + item.getQuantity());
        });
        orderRepository.delete(order);
        log.info("Orden {} cancelada por {}; stock devuelto", id, requesterId);
    }

    /** Órdenes creadas en {@code [start, end)}. Base de los reportes. */
    @Transactional(readOnly = true)
    public List<OrderDTO> getOrdersByDateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ValidationException("El rango de fechas es inválido: 'start' debe ser anterior a 'end'");
        }
        return orderRepository.findByCreatedAtRange(start, end).stream().map(OrderDTO::from).toList();
    }

    private Order findOwnedOrThrow(Long id, String requesterId, boolean requesterIsAdmin) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Orden", id));
        if (!requesterIsAdmin && !order.getUserId().equals(requesterId)) {
            log.warn("Acceso denegado: userId={} intentó acceder a la orden {} de otro usuario", requesterId, id);
            throw new AccessDeniedException("La orden pertenece a otro usuario");
        }
        return order;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
