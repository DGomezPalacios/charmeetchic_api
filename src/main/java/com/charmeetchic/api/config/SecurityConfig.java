package com.charmeetchic.api.config;

import com.charmeetchic.api.exception.InvalidJwtException;
import com.charmeetchic.api.filter.JwtValidationFilter;
import com.charmeetchic.api.service.JwtService;
import com.charmeetchic.api.service.UserService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Reglas de seguridad HTTP.
 *
 * <p><b>Azure AD -> Firebase:</b> las reglas de acceso no cambian; solo cambia quién emite los tokens que
 * valida {@link JwtValidationFilter} (Firebase Authentication en lugar de Azure AD). {@code @EnableWebSecurity}
 * se mantiene: es lo que activa Spring Security. Los roles llegan como custom claims de Firebase.
 *
 * <p>Las rutas se escriben SIN el context-path ({@code /api}), que Spring Security ya descuenta:
 * el endpoint {@code GET /api/products} se declara aquí como {@code /products}.
 *
 * <p>Matriz de acceso (el orden importa: gana la primera regla que coincide):
 * <pre>
 *   público   GET  /health, /products, /products/{id}, /products/search, /categories, /categories/{id}
 *   ADMIN     GET  /products/low-stock
 *   ADMIN     POST/PUT/DELETE /products/**, /categories/**
 *   ADMIN     PUT  /orders/{id}/status;   ADMIN  /reports/**
 *   USER      /orders/**   (ADMIN también tiene ROLE_USER)
 *   autenticado  /profile
 * </pre>
 * Además cada método de controlador restringido lleva {@code @PreAuthorize} (defensa en profundidad).
 *
 * <p>Se usan matchers explícitos ({@link #path}) en lugar de {@code requestMatchers(String)} porque la consola H2
 * registra un segundo servlet y en ese caso Spring Security no puede decidir si una ruta es de Spring MVC.
 * Son {@link PathPatternRequestMatcher}, que reemplaza a {@code AntPathRequestMatcher.antMatcher} (obsoleto y
 * marcado para eliminación desde Spring Security 6.5); igual que antes, comparan la ruta sin el context-path.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String USER = "USER";

    /** Patrones de ruta con el parser de Spring (PathPattern), válidos para cualquier servlet de la aplicación. */
    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    private final JwtService jwtService;
    private final UserService userService;
    private final HandlerExceptionResolver exceptionResolver;
    private final boolean h2ConsoleEnabled;

    public SecurityConfig(JwtService jwtService,
                          UserService userService,
                          @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
                          @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.exceptionResolver = exceptionResolver;
        this.h2ConsoleEnabled = h2ConsoleEnabled;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CorsConfigurationSource corsConfigurationSource)
            throws Exception {

        http
                // API sin estado: sin cookies de sesión ni formularios, por lo tanto CSRF no aplica.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // La consola H2 se muestra dentro de frames del mismo origen.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                // 401 / 403 con el mismo JSON que el resto de errores (GlobalExceptionHandler).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            exceptionResolver.resolveException(request, response, null,
                                    new InvalidJwtException(
                                            "Autenticación requerida: envía el ID token de Firebase como Bearer"));
                        })
                        .accessDeniedHandler((request, response, accessDenied) ->
                                exceptionResolver.resolveException(request, response, null, accessDenied)))
                .authorizeHttpRequests(auth -> {
                    // Preflight CORS y página de error de Spring Boot.
                    auth.requestMatchers(path(HttpMethod.OPTIONS, "/**")).permitAll();
                    auth.requestMatchers(path("/error")).permitAll();

                    if (h2ConsoleEnabled) {
                        auth.requestMatchers(path("/h2-console/**")).permitAll(); // solo desarrollo
                    }

                    auth.requestMatchers(path(HttpMethod.GET, "/health")).permitAll();

                    // Catálogo: lectura pública, escritura ADMIN. low-stock va ANTES de la regla pública.
                    auth.requestMatchers(path(HttpMethod.GET, "/products/low-stock")).hasRole(ADMIN);
                    auth.requestMatchers(path(HttpMethod.GET, "/products/**")).permitAll();
                    auth.requestMatchers(path(HttpMethod.POST, "/products/**")).hasRole(ADMIN);
                    auth.requestMatchers(path(HttpMethod.PUT, "/products/**")).hasRole(ADMIN);
                    auth.requestMatchers(path(HttpMethod.DELETE, "/products/**")).hasRole(ADMIN);

                    auth.requestMatchers(path(HttpMethod.GET, "/categories/**")).permitAll();
                    auth.requestMatchers(path(HttpMethod.POST, "/categories/**")).hasRole(ADMIN);
                    auth.requestMatchers(path(HttpMethod.PUT, "/categories/**")).hasRole(ADMIN);
                    auth.requestMatchers(path(HttpMethod.DELETE, "/categories/**")).hasRole(ADMIN);

                    auth.requestMatchers(path("/reports/**")).hasRole(ADMIN);

                    auth.requestMatchers(path(HttpMethod.PUT, "/orders/*/status")).hasRole(ADMIN);
                    auth.requestMatchers(path("/orders/**")).hasRole(USER);

                    auth.requestMatchers(path("/profile")).authenticated();

                    // Todo lo demás requiere autenticación (lista blanca, no lista negra).
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(new JwtValidationFilter(jwtService, userService, exceptionResolver),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static RequestMatcher path(String pattern) {
        return PATHS.matcher(pattern);
    }

    private static RequestMatcher path(HttpMethod method, String pattern) {
        return PATHS.matcher(method, pattern);
    }
}
