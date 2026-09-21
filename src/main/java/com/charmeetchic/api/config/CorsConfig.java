package com.charmeetchic.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Política CORS para el frontend (Angular en {@code http://localhost:4200} por defecto).
 * Se conecta a Spring Security en {@link SecurityConfig} ({@code http.cors(...)}), de modo que los
 * preflight {@code OPTIONS} se resuelven ANTES de exigir autenticación.
 *
 * <p>Para agregar orígenes (p. ej. el dominio de producción) usar la variable de entorno
 * {@code CORS_ALLOWED_ORIGINS} con valores separados por coma.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        // La autenticación viaja en la cabecera Authorization (Bearer), no en cookies.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
