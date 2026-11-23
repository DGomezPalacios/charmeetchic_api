package com.charmeetchic.rabbitadmin.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Convierte los <b>custom claims</b> de Firebase en roles de Spring Security ({@code ROLE_ADMIN}...).
 *
 * <p>Acepta las mismas cuatro formas que la API de inventario, para que un mismo usuario tenga el mismo rol
 * en los dos servicios:
 * {@code {"ADMIN": true}} (la que asigna {@code addAdmin.js}), {@code {"admin": true}},
 * {@code {"role": "ADMIN"}} y {@code {"roles": ["ADMIN"]}}. En los booleanos solo vale {@code true}.
 *
 * <p>Por defecto Spring lee roles del claim {@code scope}, que Firebase no usa: sin este conversor un
 * administrador real recibiría 403.
 */
public class FirebaseRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        if (Boolean.TRUE.equals(jwt.getClaims().get("ADMIN")) || Boolean.TRUE.equals(jwt.getClaims().get("admin"))) {
            roles.add("ADMIN");
        }
        if (jwt.getClaims().get("role") instanceof String role && !role.isBlank()) {
            roles.add(role.trim().toUpperCase(Locale.ROOT));
        }
        if (jwt.getClaims().get("roles") instanceof Collection<?> list) {
            list.stream()
                    .filter(String.class::isInstance)
                    .map(r -> ((String) r).trim().toUpperCase(Locale.ROOT))
                    .filter(r -> !r.isEmpty())
                    .forEach(roles::add);
        }
        return roles.stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
