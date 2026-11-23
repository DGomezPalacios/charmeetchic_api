package com.charmeetchic.api.filter;

import com.charmeetchic.api.enums.Role;
import com.charmeetchic.api.exception.InvalidJwtException;
import com.charmeetchic.api.security.AuthenticatedUser;
import com.charmeetchic.api.service.JwtService;
import com.charmeetchic.api.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Filtro que autentica cada petición con el token Bearer. Se prueban los caminos que las pruebas de integración
 * no alcanzan: qué pasa cuando falla algo que NO es el token (JWKS caído, base de datos) y que una petición
 * rechazada nunca llega al controlador ni deja un usuario autenticado en el contexto.
 */
class JwtValidationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserService userService = mock(UserService.class);
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final JwtValidationFilter filter = new JwtValidationFilter(jwtService, userService, resolver);

    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestWithoutBearer_continuesAnonymously() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/products");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("la petición sigue su curso").isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtService);
    }

    @Test
    void invalidToken_isRejectedWith401Hint_andNeverReachesTheController() throws Exception {
        MockHttpServletRequest request = bearer("token-falso");
        InvalidJwtException ex = new InvalidJwtException("Token expirado");
        when(jwtService.validateToken("token-falso")).thenThrow(ex);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("no llega al controlador").isNull();
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(resolver).resolveException(eq(request), eq(response), isNull(), eq(ex));
    }

    @Test
    void keysUnavailable_isDelegatedAsServerError_withoutBlamingTheToken() throws Exception {
        MockHttpServletRequest request = bearer("token");
        IllegalStateException ex = new IllegalStateException("No se pudieron obtener las claves de firma de Firebase");
        when(jwtService.validateToken("token")).thenThrow(ex);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .as("no se le pide al cliente renovar un token que puede ser válido").isNull();
        verify(resolver).resolveException(eq(request), eq(response), isNull(), eq(ex));
    }

    @Test
    void validToken_authenticatesWithRoles_evenIfUserSyncFails() throws Exception {
        MockHttpServletRequest request = bearer("token-valido");
        Claims claims = Jwts.claims().subject("uid-1").build();
        when(jwtService.validateToken("token-valido")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn("uid-1");
        when(jwtService.extractEmail(claims)).thenReturn("ana@charme.cl");
        when(jwtService.extractName(claims)).thenReturn("Ana");
        when(jwtService.extractRoles(claims)).thenReturn(Set.of(Role.USER, Role.ADMIN));
        doThrow(new RuntimeException("BD no disponible")).when(userService).syncUser(any(AuthenticatedUser.class));

        // Se captura la autenticación dentro de la cadena, que es lo que ve el controlador.
        Authentication[] seen = new Authentication[1];
        filter.doFilter(request, response, (req, res) ->
                seen[0] = SecurityContextHolder.getContext().getAuthentication());

        assertThat(seen[0]).isNotNull();
        assertThat(((AuthenticatedUser) seen[0].getPrincipal()).userId()).isEqualTo("uid-1");
        assertThat(seen[0].getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        verifyNoInteractions(resolver);
    }

    private static MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/profile");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }
}
