package com.charmeetchic.api.filter;

import com.charmeetchic.api.exception.InvalidJwtException;
import com.charmeetchic.api.security.AuthenticatedUser;
import com.charmeetchic.api.service.JwtService;
import com.charmeetchic.api.service.UserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.List;

/**
 * Autentica cada petición a partir de la cabecera {@code Authorization: Bearer <firebase-id-token>}.
 *
 * <p><b>Azure AD -> Firebase:</b> este filtro solo orquesta; la verificación del token (firma RS256 con las
 * claves públicas de Google, {@code iss = https://securetoken.google.com/<PROJECT_ID>}, {@code aud = <PROJECT_ID>},
 * {@code sub} y expiración) vive en {@link JwtService}, donde se puede probar sin levantar el servidor.
 * El filtro traduce el resultado a una autenticación de Spring Security con los roles que vienen en los
 * custom claims del token, para que las reglas de {@code SecurityConfig} y los {@code @PreAuthorize}
 * decidan el acceso (guardar solo el UID como atributo del request no bastaría para autorizar por rol).
 *
 * <ul>
 *   <li>Sin cabecera Bearer: la petición sigue como anónima (las reglas de {@code SecurityConfig}
 *       deciden si el endpoint es público).</li>
 *   <li>Con token válido: se crea la autenticación con los roles del token ({@code ROLE_ADMIN},
 *       {@code ROLE_USER}) y se sincroniza el usuario en base de datos.</li>
 *   <li>Con token inválido (firma falsa, expirado, de otro proyecto de Firebase...): 401 inmediato, incluso
 *       en endpoints públicos (un token roto indica un cliente mal configurado y conviene saberlo cuanto antes).
 *       El mensaje distingue el motivo, p. ej. "El token de Firebase ha expirado".</li>
 * </ul>
 *
 * <p>NO es un bean de Spring a propósito: si lo fuera, Spring Boot lo registraría además como filtro
 * de servlet global. {@code SecurityConfig} lo instancia y lo inserta solo en la cadena de seguridad.
 * Los errores se delegan al {@link HandlerExceptionResolver} para que salgan con el mismo formato
 * JSON que el resto de la API ({@code GlobalExceptionHandler}).
 */
@Slf4j
public class JwtValidationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserService userService;
    private final HandlerExceptionResolver exceptionResolver;

    public JwtValidationFilter(JwtService jwtService, UserService userService,
                               HandlerExceptionResolver exceptionResolver) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            chain.doFilter(request, response);
            return;
        }

        try {
            authenticate(request, header.substring(BEARER_PREFIX.length()).trim());
        } catch (RuntimeException ex) {
            SecurityContextHolder.clearContext();
            if (ex instanceof InvalidJwtException) {
                log.debug("Token de Firebase rechazado en {} {}: {}", request.getMethod(), request.getRequestURI(),
                        ex.getMessage());
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            }
            exceptionResolver.resolveException(request, response, null, ex);
            return;
        }

        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        Claims claims = jwtService.validateToken(token);
        AuthenticatedUser user = new AuthenticatedUser(
                jwtService.extractUserId(claims),
                jwtService.extractEmail(claims),
                jwtService.extractName(claims),
                jwtService.extractRoles(claims));

        log.debug("Token de Firebase válido: uid={}, roles={}", user.userId(), user.roles());
        syncUserQuietly(user);

        List<GrantedAuthority> authorities = user.roles().stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** Un fallo al registrar el usuario no debe impedir que una petición con token válido se atienda. */
    private void syncUserQuietly(AuthenticatedUser user) {
        try {
            userService.syncUser(user);
        } catch (RuntimeException e) {
            log.warn("No se pudo sincronizar el usuario {}: {}", user.userId(), e.getMessage());
        }
    }
}
