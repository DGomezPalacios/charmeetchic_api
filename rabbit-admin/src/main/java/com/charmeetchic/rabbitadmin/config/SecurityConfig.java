package com.charmeetchic.rabbitadmin.config;

import com.charmeetchic.rabbitadmin.security.FirebaseRolesConverter;
import com.charmeetchic.rabbitadmin.security.FirebaseTokenValidators;
import com.charmeetchic.rabbitadmin.security.JsonSecurityErrorHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad como <b>OAuth2 Resource Server</b>: cada petición trae {@code Authorization: Bearer <ID token de
 * Firebase>} y Spring Security lo valida solo (no hay filtro propio).
 *
 * <p>Flujo: {@code BearerTokenAuthenticationFilter} extrae el token -> {@link JwtDecoder} verifica la firma RS256
 * con las claves públicas de Google (JWKS, descargadas en el primer uso y cacheadas) y aplica
 * {@link FirebaseTokenValidators} (exp, iss, aud, sub) -> {@link FirebaseRolesConverter} convierte los custom
 * claims en roles -> la regla {@code hasRole("ADMIN")} decide.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        JsonSecurityErrorHandler errors = new JsonSecurityErrorHandler(objectMapper);
        JwtAuthenticationConverter authConverter = new JwtAuthenticationConverter();
        authConverter.setJwtGrantedAuthoritiesConverter(new FirebaseRolesConverter());

        http
                // API sin estado y sin formularios: no hay cookies de sesión que proteger con CSRF.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health").permitAll() // healthcheck de Docker
                        .requestMatchers("/error").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authConverter))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }

    /**
     * Decoder de ID tokens de Firebase. Se define a mano (en vez de solo {@code issuer-uri} en el yml) para
     * dejar explícitas las reglas, sobre todo la audiencia, y para no depender de red al arrancar: las claves
     * se descargan recién con el primer token.
     */
    @Bean
    public JwtDecoder jwtDecoder(AppProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.security().jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(FirebaseTokenValidators.forProject(props.security().firebaseProjectId()));
        return decoder;
    }
}
