package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.UserDTO;
import com.charmeetchic.api.entity.User;
import com.charmeetchic.api.security.AuthenticatedUser;
import com.charmeetchic.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Mantiene una copia local de los usuarios que llegan con un token válido. Firebase sigue siendo la
 * fuente de verdad: aquí solo se registra quién ha usado la API y cuándo (útil para reportes o para
 * asociar datos futuros: carrito, direcciones, pagos...).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /** {@code lastLogin} no se reescribe en cada petición, solo si pasó al menos este tiempo. */
    static final Duration LAST_LOGIN_REFRESH = Duration.ofMinutes(5);

    private final UserRepository userRepository;

    /**
     * Crea el usuario si es la primera vez que se le ve; si no, actualiza email, nombre y roles cuando
     * cambiaron en Firebase (email, displayName, custom claims) y refresca {@code lastLogin} (con la limitación de {@link #LAST_LOGIN_REFRESH}).
     */
    @Transactional
    public User syncUser(AuthenticatedUser principal) {
        LocalDateTime now = LocalDateTime.now();
        return userRepository.findByUserId(principal.userId())
                .map(user -> refresh(user, principal, now))
                .orElseGet(() -> {
                    User created = userRepository.save(User.builder()
                            .userId(principal.userId())
                            .email(principal.email())
                            .name(principal.name())
                            .roles(EnumSet.copyOf(principal.roles()))
                            .lastLogin(now)
                            .build());
                    log.info("Usuario nuevo registrado: userId={}, roles={}", created.getUserId(), created.getRoles());
                    return created;
                });
    }

    /** Perfil del usuario autenticado (lo crea si aún no existe). */
    @Transactional
    public UserDTO getProfile(AuthenticatedUser principal) {
        return UserDTO.from(syncUser(principal));
    }

    private User refresh(User user, AuthenticatedUser principal, LocalDateTime now) {
        if (principal.email() != null && !Objects.equals(user.getEmail(), principal.email())) {
            user.setEmail(principal.email());
        }
        if (principal.name() != null && !Objects.equals(user.getName(), principal.name())) {
            user.setName(principal.name());
        }
        if (!user.getRoles().equals(principal.roles())) {
            log.info("Roles actualizados para userId={}: {} -> {}", user.getUserId(), user.getRoles(), principal.roles());
            user.getRoles().clear();
            user.getRoles().addAll(principal.roles());
        }
        if (user.getLastLogin() == null || user.getLastLogin().plus(LAST_LOGIN_REFRESH).isBefore(now)) {
            user.setLastLogin(now);
        }
        return user;
    }
}
