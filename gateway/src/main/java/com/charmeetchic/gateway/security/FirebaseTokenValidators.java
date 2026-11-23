package com.charmeetchic.gateway.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

import java.util.Collection;

/**
 * Reglas de un ID token de Firebase (las mismas que rabbit-admin), además de la firma RS256:
 * {@code exp}/{@code nbf} vigentes, {@code iss = https://securetoken.google.com/<PROJECT_ID>},
 * {@code aud} contiene {@code <PROJECT_ID>} (Spring no lo revisa por defecto) y {@code sub} presente.
 *
 * <p>Está duplicada en cada microservicio a propósito: son apps independientes y no comparten librerías.
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
