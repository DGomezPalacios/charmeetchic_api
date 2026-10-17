package com.charmeetchic.rabbitadmin.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

import java.util.Collection;

/**
 * Reglas que debe cumplir un ID token de Firebase, además de la firma RS256 (la verifica el decoder con las
 * claves públicas de Google):
 * <ul>
 *   <li>{@code exp} / {@code nbf}: no vencido (con 60 s de tolerancia de reloj).</li>
 *   <li>{@code iss} = {@code https://securetoken.google.com/<PROJECT_ID>}</li>
 *   <li>{@code aud} contiene {@code <PROJECT_ID>}: evita aceptar tokens emitidos para OTRO proyecto de
 *       Firebase, que también vienen firmados por Google. Spring no lo revisa por defecto.</li>
 *   <li>{@code sub} (UID del usuario) presente.</li>
 * </ul>
 */
public final class FirebaseTokenValidators {

    public static final String ISSUER_PREFIX = "https://securetoken.google.com/";

    private FirebaseTokenValidators() {
    }

    public static OAuth2TokenValidator<Jwt> forProject(String projectId) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER_PREFIX + projectId),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(projectId)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()));
    }
}
