package com.charmeetchic.api.service;

import com.charmeetchic.api.config.JwtConfig;
import com.charmeetchic.api.enums.Role;
import com.charmeetchic.api.exception.InvalidJwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Valida ID tokens de <b>Firebase Authentication</b> y extrae los datos del usuario.
 *
 * <h3>Qué se comprueba (checklist oficial de Firebase para verificar ID tokens)</h3>
 * <ol>
 *   <li><b>Firma RS256</b>: la clave se busca por {@code kid} en el JWKS público de Google
 *       ({@link JwksKeyProvider}). Cualquier otro algoritmo (HS256, none...) se rechaza.</li>
 *   <li><b>{@code exp}</b> presente y en el futuro; <b>{@code iat}</b> y <b>{@code auth_time}</b> no en el futuro.</li>
 *   <li><b>{@code iss}</b> = {@code https://securetoken.google.com/<PROJECT_ID>}.</li>
 *   <li><b>{@code aud}</b> = {@code <PROJECT_ID>}.</li>
 *   <li><b>{@code sub}</b> no vacío: es el UID de Firebase del usuario.</li>
 * </ol>
 * Los pasos 2-5 son los mismos que hacía la versión para Azure AD con otros valores; lo que cambia es
 * el emisor, la audiencia, el origen de las claves y de dónde salen el id de usuario y los roles.
 *
 * <h3>Claims usados</h3>
 * <ul>
 *   <li>{@code sub} -> UID de Firebase (antes {@code oid} de Azure)</li>
 *   <li>{@code email}, {@code name} -> perfil (pueden faltar, p. ej. en usuarios anónimos)</li>
 *   <li>Roles: <b>custom claims</b> de Firebase asignados con el Admin SDK: {@code roles: ["ADMIN"]},
 *       {@code role: "ADMIN"}, {@code admin: true} o {@code ADMIN: true}. Sin roles reconocidos se asume USER.</li>
 * </ul>
 *
 * <p>Los métodos {@code extract*} y {@code getTokenExpiration} tienen una versión que recibe los
 * {@link Claims} ya validados (para no verificar la firma varias veces) y otra que recibe el token.
 */
@Slf4j
@Service
public class JwtService {

    private static final int MIN_LOCAL_SECRET_BYTES = 32;

    /** Custom claim booleano {@code {"ADMIN": true}}, el que asigna {@code addAdmin.js}. */
    private static final String ADMIN_CLAIM = "ADMIN";

    private final JwtConfig config;
    private final JwksKeyProvider jwksKeyProvider;
    private JwtParser parser;

    public JwtService(JwtConfig config, JwksKeyProvider jwksKeyProvider) {
        this.config = config;
        this.jwksKeyProvider = jwksKeyProvider;
    }

    @PostConstruct
    void init() {
        if (config.getMode() == JwtConfig.Mode.LOCAL) {
            byte[] secret = config.getLocalSecret() == null
                    ? new byte[0] : config.getLocalSecret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < MIN_LOCAL_SECRET_BYTES) {
                throw new IllegalStateException("app.jwt.local-secret (JWT_LOCAL_SECRET) debe tener al menos "
                        + MIN_LOCAL_SECRET_BYTES + " caracteres cuando app.jwt.mode=local");
            }
            parser = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(secret))
                    .clockSkewSeconds(config.getClockSkewSeconds())
                    .build();
            log.warn("JWT en modo LOCAL (HS256): solo para desarrollo. NO usar en producción.");
        } else {
            parser = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            // Firebase firma SIEMPRE con RS256. Aceptar otro algoritmo abriría la puerta a
                            // ataques de confusión (p. ej. un HS256 firmado con la clave pública como secreto).
                            if (!"RS256".equals(header.getAlgorithm())) {
                                throw new InvalidJwtException("Algoritmo de firma no permitido: " + header.getAlgorithm());
                            }
                            return jwksKeyProvider.getKey(header.getKeyId());
                        }
                    })
                    .clockSkewSeconds(config.getClockSkewSeconds())
                    .build();
            if (config.isFirebaseConfigured()) {
                log.info("JWT en modo FIREBASE: issuer={}, audience={}", config.issuer(), config.audience());
            } else {
                log.warn("JWT en modo FIREBASE pero falta firebase.project-id (FIREBASE_PROJECT_ID): "
                        + "los endpoints públicos funcionan, pero todo token será rechazado.");
            }
        }
    }

    /**
     * Valida firma, expiración y (en modo Firebase) emisor, audiencia, {@code iat}, {@code auth_time} y {@code sub}.
     *
     * @return los claims del token, ya confiables
     * @throws InvalidJwtException si el token no es válido por cualquier motivo
     */
    public Claims validateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidJwtException("Token ausente");
        }
        if (config.getMode() == JwtConfig.Mode.FIREBASE && !config.isFirebaseConfigured()) {
            throw new InvalidJwtException("El servidor no tiene configurado el proyecto de Firebase");
        }

        Claims claims;
        try {
            claims = parser.parseSignedClaims(token).getPayload();
        } catch (InvalidJwtException e) {
            throw e;
        } catch (ExpiredJwtException e) {
            // Los ID tokens de Firebase duran 1 hora; el cliente debe renovarlo con getIdToken().
            throw new InvalidJwtException("El token de Firebase ha expirado; obtén uno nuevo con getIdToken()", e);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Token rechazado: {}", e.getMessage());
            throw new InvalidJwtException("Token de Firebase inválido", e);
        }

        if (claims.getExpiration() == null) {
            throw new InvalidJwtException("El token no tiene fecha de expiración");
        }
        if (config.getMode() == JwtConfig.Mode.FIREBASE) {
            verifyFirebaseClaims(claims);
        }
        if (extractUserId(claims) == null) {
            throw new InvalidJwtException("El token no contiene el UID de Firebase (sub)");
        }
        log.debug("Token válido: uid={}, expira={}", claims.getSubject(), claims.getExpiration().toInstant());
        return claims;
    }

    /** Comprobaciones específicas de Firebase que jjwt no hace por sí solo. */
    private void verifyFirebaseClaims(Claims claims) {
        if (!config.issuer().equals(claims.getIssuer())) {
            throw new InvalidJwtException("El token no pertenece a este proyecto de Firebase (issuer inválido)");
        }
        Set<String> audiences = claims.getAudience();
        if (audiences == null || !audiences.contains(config.audience())) {
            throw new InvalidJwtException("El token no pertenece a este proyecto de Firebase (audience inválida)");
        }
        Instant latestAllowed = Instant.now().plusSeconds(config.getClockSkewSeconds());
        if (claims.getIssuedAt() != null && claims.getIssuedAt().toInstant().isAfter(latestAllowed)) {
            throw new InvalidJwtException("El token tiene una fecha de emisión (iat) en el futuro");
        }
        if (claims.get("auth_time") instanceof Number authTime
                && Instant.ofEpochSecond(authTime.longValue()).isAfter(latestAllowed)) {
            throw new InvalidJwtException("El token tiene una fecha de autenticación (auth_time) en el futuro");
        }
    }

    // ------------------------------------------------------------------ extracción de datos

    /** UID de Firebase: claim {@code sub}. Devuelve null si falta o está vacío. */
    public String extractUserId(Claims claims) {
        String sub = claims.getSubject();
        return sub != null && !sub.isBlank() ? sub : null;
    }

    public String extractUserId(String token) {
        return extractUserId(validateToken(token));
    }

    /**
     * Roles a partir de los <b>custom claims</b> de Firebase. Se admiten cuatro formas (las que suelen
     * usarse al llamar a {@code setCustomUserClaims}):
     * {@code {"roles": ["ADMIN"]}}, {@code {"role": "ADMIN"}}, {@code {"admin": true}} y
     * {@code {"ADMIN": true}}. En los booleanos solo vale {@code true}: {@code "true"} o {@code false} no dan rol.
     * Valores desconocidos se ignoran (sin distinguir mayúsculas). Todo usuario autenticado es al menos
     * USER, y un ADMIN también es USER.
     */
    public Set<Role> extractRoles(Claims claims) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        Object raw = claims.get("roles");
        if (raw instanceof Collection<?> values) {
            values.forEach(v -> parseRole(String.valueOf(v), roles));
        } else if (raw instanceof String single) {
            parseRole(single, roles);
        }
        if (claims.get("role") instanceof String single) {
            parseRole(single, roles);
        }
        if (Boolean.TRUE.equals(claims.get("admin")) || Boolean.TRUE.equals(claims.get(ADMIN_CLAIM))) {
            roles.add(Role.ADMIN);
        }
        roles.add(Role.USER);
        return roles;
    }

    public Set<Role> extractRoles(String token) {
        return extractRoles(validateToken(token));
    }

    /** Claim {@code email} (ausente en usuarios anónimos o de teléfono). */
    public String extractEmail(Claims claims) {
        String email = claims.get("email", String.class);
        return email != null && !email.isBlank() ? email : null;
    }

    public String extractEmail(String token) {
        return extractEmail(validateToken(token));
    }

    /** Claim {@code name} (nombre a mostrar; puede faltar). */
    public String extractName(Claims claims) {
        return claims.get("name", String.class);
    }

    public Instant getTokenExpiration(Claims claims) {
        return claims.getExpiration().toInstant();
    }

    public Instant getTokenExpiration(String token) {
        return getTokenExpiration(validateToken(token));
    }

    private static void parseRole(String value, Set<Role> target) {
        try {
            target.add(Role.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            // valor que esta API no usa
        }
    }
}
