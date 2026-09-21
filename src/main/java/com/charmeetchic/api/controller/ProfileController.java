package com.charmeetchic.api.controller;

import com.charmeetchic.api.dto.UserDTO;
import com.charmeetchic.api.security.AuthenticatedUser;
import com.charmeetchic.api.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perfil del usuario autenticado: {@code GET /api/profile}. Útil para que el frontend sepa qué roles
 * tiene el usuario logueado (mostrar u ocultar la administración).
 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<UserDTO> getProfile(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(userService.getProfile(user));
    }
}
