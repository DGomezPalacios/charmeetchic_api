package com.charmeetchic.api.controller;

import com.charmeetchic.api.dto.OrderDTO;
import com.charmeetchic.api.dto.OrderRequestDTO;
import com.charmeetchic.api.dto.OrderStatusDTO;
import com.charmeetchic.api.security.AuthenticatedUser;
import com.charmeetchic.api.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Órdenes de compra. URL base: {@code /api/orders}. Requiere autenticación (rol USER; un ADMIN también
 * lo tiene). El usuario se toma SIEMPRE del token, nunca del cuerpo de la petición.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('USER')")
public class OrderController {

    private final OrderService orderService;

    /** POST /api/orders — crea una orden PENDING para el usuario del token y descuenta stock. */
    @PostMapping
    public ResponseEntity<OrderDTO> createOrder(@AuthenticationPrincipal AuthenticatedUser user,
                                                @Valid @RequestBody OrderRequestDTO request) {
        OrderDTO created = orderService.createOrder(user.userId(), request.shippingAddress(), request.items());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** GET /api/orders — mis órdenes, la más reciente primero. */
    @GetMapping
    public ResponseEntity<List<OrderDTO>> getMyOrders(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(orderService.getOrdersByUser(user.userId()));
    }

    /** GET /api/orders/{id} — una orden mía (un ADMIN puede ver cualquiera). */
    @GetMapping("/{id}")
    public ResponseEntity<OrderDTO> getOrderById(@AuthenticationPrincipal AuthenticatedUser user,
                                                 @PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id, user.userId(), user.isAdmin()));
    }

    /** PUT /api/orders/{id}/status — ADMIN. Body: {@code {"status":"PROCESSING"}}. */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderDTO> updateOrderStatus(@PathVariable Long id,
                                                      @Valid @RequestBody OrderStatusDTO body) {
        return ResponseEntity.ok(orderService.updateOrderStatus(id, body.status()));
    }

    /** DELETE /api/orders/{id} — cancela una orden PENDING mía y devuelve el stock. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrder(@AuthenticationPrincipal AuthenticatedUser user,
                                            @PathVariable Long id) {
        orderService.deleteOrder(id, user.userId(), user.isAdmin());
        return ResponseEntity.noContent().build();
    }
}
