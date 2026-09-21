package com.charmeetchic.api.dto;

import com.charmeetchic.api.entity.User;
import com.charmeetchic.api.enums.Role;

import java.time.LocalDateTime;
import java.util.Set;

/** Perfil del usuario autenticado ({@code GET /profile}). */
public record UserDTO(
        String userId,
        String email,
        String name,
        Set<Role> roles,
        LocalDateTime createdAt,
        LocalDateTime lastLogin) {

    public static UserDTO from(User u) {
        return new UserDTO(u.getUserId(), u.getEmail(), u.getName(), Set.copyOf(u.getRoles()),
                u.getCreatedAt(), u.getLastLogin());
    }
}
