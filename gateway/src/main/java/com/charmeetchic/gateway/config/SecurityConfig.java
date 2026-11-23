package com.charmeetchic.gateway.config;

import com.charmeetchic.gateway.security.FirebaseTokenValidators;
import com.charmeetchic.gateway.security.JsonAuthenticationEntryPoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Seguridad del gateway (WebFlux, reactiva).
 *
 * <p>Orden en que se evalúa cada petición:
 * <ol>
 *   <li><b>CORS</b>: un preflight {@code OPTIONS} de un origen permitido se responde aquí mismo; uno de un
 *       origen no permitido recibe 403. Nunca llegan a los servicios.</li>
 *   <li><b>Token</b>: si hay {@code Authorization: Bearer ...}, se valida (firma RS256 con el JWKS de Google +
 *       {@link FirebaseTokenValidators}). Inválido -> 401 JSON, y la petición NO se reenvía.</li>
 *   <li><b>Reglas</b>: rutas públicas ({@code app.security.public-paths}) pasan sin token; todo lo demás
 *       exige estar autenticado. Los ROLES (ADMIN...) los decide cada servicio, que vuelve a validar.</li>
 * </ol>
 *
 * <p>El token se reenvía intacto al servicio destino ({@code Authorization} no se modifica).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, GatewayProperties props,
                                                         ObjectMapper objectMapper) {
        JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint(objectMapper);
        String[] publicPaths = props.security().publicPaths().toArray(String[]::new);

        return http
                // Sin sesiones ni cookies: el token viaja en cada petición, CSRF no aplica.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(Customizer.withDefaults()) // usa el bean corsConfigurationSource
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(publicPaths).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .build();
    }

    /** Decoder reactivo de ID tokens de Firebase (las claves se descargan con el primer token y se cachean). */
    @Bean
    public ReactiveJwtDecoder jwtDecoder(GatewayProperties props) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(props.security().jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(FirebaseTokenValidators.forProject(props.security().firebaseProjectId()));
        return decoder;
    }

    /**
     * CORS para el frontend. Solo orígenes exactos (nada de "*"): Firebase Hosting en producción y Angular
     * local. {@code allowCredentials=false} porque la autenticación va en el header Authorization, no en cookies.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(GatewayProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.cors().allowedOrigins().stream().map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setExposedHeaders(List.of("Location")); // para leer el id del 202 de reportes asíncronos
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
