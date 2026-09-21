package com.charmeetchic.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Parámetros de validación JWT.
 *
 * <p><b>Azure AD -> Firebase:</b> esta clase antes describía un tenant de Azure AD (tenant id, client id,
 * emisores v1/v2, audiencias {@code api://...}). Un token de Firebase Authentication es más simple: todo
 * gira en torno al <b>Project ID</b> de Firebase ({@code firebase.project-id}):
 * <ul>
 *   <li>{@code iss} = {@code https://securetoken.google.com/<PROJECT_ID>}</li>
 *   <li>{@code aud} = {@code <PROJECT_ID>}</li>
 *   <li>{@code sub} = UID del usuario en Firebase</li>
 *   <li>firma RS256 con las claves públicas de Google (JWKS de {@code securetoken@system.gserviceaccount.com})</li>
 * </ul>
 *
 * <p>Modos ({@code app.jwt.mode}):
 * <ul>
 *   <li>{@code FIREBASE} (por defecto): valida tokens de Firebase como se describe arriba.</li>
 *   <li>{@code LOCAL}: valida tokens HS256 firmados con {@code localSecret}. SOLO para desarrollo y tests
 *       cuando no hay un proyecto de Firebase a mano. Nunca usar en producción.</li>
 * </ul>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtConfig {

    public enum Mode {FIREBASE, LOCAL}

    /** Prefijo del emisor de todos los ID tokens de Firebase. */
    public static final String FIREBASE_ISSUER_PREFIX = "https://securetoken.google.com/";

    /** Claves públicas (formato JWKS) con las que Google firma los ID tokens de Firebase. */
    public static final String FIREBASE_JWKS_URI =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    private Mode mode = Mode.FIREBASE;

    /** Project ID de Firebase (Project settings -> General). Sale de {@code firebase.project-id}. */
    @Value("${firebase.project-id:charme-et-chic}")
    private String projectId = "";

    /** Secreto HMAC (mínimo 32 caracteres) para el modo LOCAL. */
    private String localSecret = "";

    /** Tolerancia ante desfase de reloj al validar {@code exp}/{@code iat}/{@code auth_time}. */
    private long clockSkewSeconds = 60;

    /** Intervalo mínimo entre recargas del JWKS (protege contra tokens con {@code kid} inventado). */
    private long jwksMinRefreshSeconds = 300;

    public boolean isFirebaseConfigured() {
        return projectId != null && !projectId.isBlank();
    }

    /** Emisor esperado: {@code https://securetoken.google.com/<PROJECT_ID>}. */
    public String issuer() {
        return FIREBASE_ISSUER_PREFIX + projectId;
    }

    /** Audiencia esperada: el Project ID a secas. */
    public String audience() {
        return projectId;
    }

    public String jwksUri() {
        return FIREBASE_JWKS_URI;
    }
}
