package com.charmeetchic.api.security;

import com.charmeetchic.api.enums.Role;

import java.util.Set;

/**
 * Identidad del usuario que hace la petición, extraída de un JWT ya validado. Es el
 * {@code principal} de la autenticación de Spring Security; los controladores lo reciben con
 * {@code @AuthenticationPrincipal AuthenticatedUser user}.
 *
 * @param userId UID del usuario en Firebase Authentication (claim {@code sub} del ID token)
 */
public record AuthenticatedUser(String userId, String email, String name, Set<Role> roles) {

    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }
}
