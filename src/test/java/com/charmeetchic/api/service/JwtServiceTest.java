package com.charmeetchic.api.service;

import com.charmeetchic.api.config.JwtConfig;
import com.charmeetchic.api.enums.Role;
import com.charmeetchic.api.exception.InvalidJwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pruebas de validación de tokens. El modo FIREBASE se prueba con un par de claves RSA generado aquí que
 * juega el papel de "Google", y un {@link JwksKeyProvider} simulado (mock de la descarga de claves): se
 * verifica nuestra lógica (firma, emisor, audiencia, sub, iat, algoritmo, expiración) sin depender de la red
 * ni de un proyecto real de Firebase.
 */
class JwtServiceTest {

    private static final String LOCAL_SECRET = "local-test-secret-local-test-secret-1234";
    private static final String PROJECT_ID = "charmeetchic-test";
    private static final String ISSUER = "https://securetoken.google.com/" + PROJECT_ID;

    // ================================================================== modo LOCAL

    @Nested
    class LocalMode {

        private final JwtService service = localService();

        @Test
        void validToken_exposesUserRolesAndExpiration() {
            String token = localBuilder("uid-1", 3600)
                    .claim("email", "ana@charme.cl").claim("name", "Ana")
                    .claim("roles", List.of("ADMIN")).compact();

            Claims claims = service.validateToken(token);

            assertThat(service.extractUserId(claims)).isEqualTo("uid-1");
            assertThat(service.extractEmail(claims)).isEqualTo("ana@charme.cl");
            assertThat(service.extractName(claims)).isEqualTo("Ana");
            assertThat(service.extractRoles(claims)).containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
            assertThat(service.getTokenExpiration(claims)).isAfter(Instant.now());
        }

        @Test
        void stringOverloads_validateAndExtract() {
            String token = localBuilder("uid-1", 3600).claim("email", "a@b.cl").claim("roles", List.of("USER")).compact();

            assertThat(service.extractUserId(token)).isEqualTo("uid-1");
            assertThat(service.extractEmail(token)).isEqualTo("a@b.cl");
            assertThat(service.extractRoles(token)).containsExactly(Role.USER);
            assertThat(service.getTokenExpiration(token)).isAfter(Instant.now());
        }

        @Test
        void expiredToken_isRejected() {
            String token = localBuilder("uid-1", -3600).compact();

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(InvalidJwtException.class)
                    .hasMessageContaining("expirado");
        }

        @Test
        void tokenWithoutExpiration_isRejected() {
            String token = Jwts.builder().subject("uid-1").signWith(localKey()).compact();

            assertThatThrownBy(() -> service.validateToken(token)).isInstanceOf(InvalidJwtException.class);
        }

        @Test
        void tokenWithoutSubject_isRejected() {
            String token = Jwts.builder().claim("oid", "azure-style-id").expiration(inSeconds(3600))
                    .signWith(localKey()).compact();

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(InvalidJwtException.class).hasMessageContaining("sub");
        }

        @Test
        void tamperedSignature_isRejected() {
            SecretKey otherKey = Keys.hmacShaKeyFor("another-secret-another-secret-another-1".getBytes(StandardCharsets.UTF_8));
            String token = localBuilder("uid-1", 3600).signWith(otherKey).compact();

            assertThatThrownBy(() -> service.validateToken(token)).isInstanceOf(InvalidJwtException.class);
        }

        @Test
        void garbageAndBlankTokens_areRejected() {
            assertThatThrownBy(() -> service.validateToken("esto.no.es-un-jwt")).isInstanceOf(InvalidJwtException.class);
            assertThatThrownBy(() -> service.validateToken("")).isInstanceOf(InvalidJwtException.class);
            assertThatThrownBy(() -> service.validateToken(null)).isInstanceOf(InvalidJwtException.class);
        }

        @Test
        void unsignedToken_isRejected() {
            String unsigned = Jwts.builder().subject("uid-1").expiration(inSeconds(3600)).compact();

            assertThatThrownBy(() -> service.validateToken(unsigned)).isInstanceOf(InvalidJwtException.class);
        }

        @Test
        void shortSecret_failsFastAtStartup() {
            JwtConfig config = new JwtConfig();
            config.setMode(JwtConfig.Mode.LOCAL);
            config.setLocalSecret("too-short");

            assertThatThrownBy(() -> new JwtService(config, mock(JwksKeyProvider.class)).init())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ================================================================== extracción de roles (custom claims)

    @Nested
    class RolesFromCustomClaims {

        private final JwtService service = localService();

        @Test
        void noRoleClaims_defaultsToUser() {
            assertThat(roles(localBuilder("uid-1", 3600))).containsExactly(Role.USER);
        }

        @Test
        void rolesArray_isSupported() {
            assertThat(roles(localBuilder("uid-1", 3600).claim("roles", List.of("admin", "Reader"))))
                    .containsExactlyInAnyOrder(Role.ADMIN, Role.USER); // "Reader" se ignora; ADMIN implica USER
        }

        @Test
        void rolesAsSingleString_isSupported() {
            assertThat(roles(localBuilder("uid-1", 3600).claim("roles", "ADMIN")))
                    .containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
        }

        @Test
        void singleRoleClaim_isSupported() {
            assertThat(roles(localBuilder("uid-1", 3600).claim("role", "ADMIN")))
                    .containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
        }

        @Test
        void adminBooleanClaim_isSupported() {
            assertThat(roles(localBuilder("uid-1", 3600).claim("admin", true)))
                    .containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
        }

        @Test
        void adminFalseOrWrongType_doesNotGrantAdmin() {
            assertThat(roles(localBuilder("uid-1", 3600).claim("admin", false))).containsExactly(Role.USER);
            assertThat(roles(localBuilder("uid-1", 3600).claim("admin", "true"))).containsExactly(Role.USER);
        }

        private java.util.Set<Role> roles(JwtBuilder builder) {
            return service.extractRoles(service.validateToken(builder.compact()));
        }
    }

    // ================================================================== modo FIREBASE

    @Nested
    class FirebaseMode {

        private final KeyPair googleKeys = generateRsaKeyPair();
        private final JwksKeyProvider jwks = mock(JwksKeyProvider.class);
        private final JwtService service = firebaseService(jwks, PROJECT_ID);

        FirebaseMode() {
            when(jwks.getKey("kid-1")).thenReturn(googleKeys.getPublic());
        }

        @Test
        void validToken_isAccepted_andExposesFirebaseIdentity() {
            String token = firebaseToken("uid-firebase", "kid-1", 3600)
                    .claim("email", "ana@charme.cl").claim("name", "Ana")
                    .claim("roles", List.of("ADMIN")).compact();

            Claims claims = service.validateToken(token);

            assertThat(service.extractUserId(claims)).isEqualTo("uid-firebase");
            assertThat(service.extractEmail(claims)).isEqualTo("ana@charme.cl");
            assertThat(service.extractName(claims)).isEqualTo("Ana");
            assertThat(service.extractRoles(claims)).containsExactlyInAnyOrder(Role.ADMIN, Role.USER);
        }

        @Test
        void anonymousUser_withoutEmail_isAccepted() {
            Claims claims = service.validateToken(firebaseToken("anon-uid", "kid-1", 3600).compact());

            assertThat(service.extractEmail(claims)).isNull();
            assertThat(service.extractRoles(claims)).containsExactly(Role.USER);
        }

        @Test
        void wrongIssuer_isRejected() {
            String token = firebaseToken("uid", "kid-1", 3600).issuer("https://securetoken.google.com/otro-proyecto").compact();

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(InvalidJwtException.class).hasMessageContaining("issuer");
        }

        @Test
        void azureIssuer_isRejected() {
            String token = firebaseToken("uid", "kid-1", 3600)
                    .issuer("https://login.microsoftonline.com/tenant/v2.0").compact();

            assertThatThrownBy(() -> service.validateToken(token)).isInstanceOf(InvalidJwtException.class);
        }

        @Test
        void wrongAudience_isRejected() {
            String token = baseFirebaseBuilder("uid", "kid-1", 3600).issuer(ISSUER)
                    .audience().add("otro-proyecto").and().compact();

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(InvalidJwtException.class).hasMessageContaining("audience");
        }

        @Test
        void expiredToken_isRejected_withRenewHint() {
            String token = firebaseToken("uid", "kid-1", -3600).compact();

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(InvalidJwtException.class).hasMessageContaining("expirado");
        }

        @Test
        void tokenWithoutExpiration_isRejected() {
            String token = Jwts.builder().header().keyId("kid-1").and()
                    .issuer(ISSUER).audience().add(PROJECT_ID).and().subject("uid")
                    .signWith(googleKeys.getPrivate(), Jwts.SIG.RS256).compact();

            assertThatThrownBy(() -> service.validateToken(token)).isInstanceOf(InvalidJwtException.class);
        }

        @Test
        void missingOrBlankSubject_isRejected() {
            String noSub = firebaseToken(null, "kid-1", 3600).compact();
            String blankSub = firebaseToken("  ", "kid-1", 3600).compact();

            assertThatThrownBy(() -> service.validateToken(noSub))
                    .isInstanceOf(InvalidJwtException.class).hasMessageContaining("sub");
            assertThatThrownBy(() -> service.validateToken(blankSub)).isInstanceOf(InvalidJwtException.class);
        }

        @Test
        void issuedInTheFuture_isRejected() {
            String token = firebaseToken("uid", "kid-1", 7200).issuedAt(inSeconds(3600)).compact();

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(InvalidJwtException.class).hasMessageContaining("iat");
        }

        @Test
        void authTimeInTheFuture_isRejected() {
            long future = Instant.now().plusSeconds(3600).getEpochSecond();
            String token = firebaseToken("uid", "kid-1", 7200).claim("auth_time", future).compact();

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(InvalidJwtException.class).hasMessageContaining("auth_time");
        }

        /** Token con el issuer/audience/sub perfectos pero firmado por otra clave: es la falsificación clásica. */
        @Test
        void forgedToken_signedWithAnotherRsaKey_isRejected() {
            String forged = baseFirebaseBuilder("uid-victima", "kid-1", 3600).issuer(ISSUER)
                    .audience().add(PROJECT_ID).and().claim("roles", List.of("ADMIN"))
                    .signWith(generateRsaKeyPair().getPrivate(), Jwts.SIG.RS256).compact();

            assertThatThrownBy(() -> service.validateToken(forged)).isInstanceOf(InvalidJwtException.class);
        }

        @Test
        void tamperedPayload_isRejected() {
            String token = firebaseToken("uid-normal", "kid-1", 3600).compact();
            String[] parts = token.split("\\.");
            String evilPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + PROJECT_ID + "\",\"sub\":\"admin-uid\","
                            + "\"roles\":[\"ADMIN\"],\"exp\":" + Instant.now().plusSeconds(3600).getEpochSecond() + "}")
                            .getBytes(StandardCharsets.UTF_8));
            String tampered = parts[0] + "." + evilPayload + "." + parts[2];

            assertThatThrownBy(() -> service.validateToken(tampered)).isInstanceOf(InvalidJwtException.class);
        }

        @Test
        void unknownKid_isRejected() {
            when(jwks.getKey("kid-unknown")).thenThrow(new InvalidJwtException("Clave de firma desconocida para este token"));
            String token = firebaseToken("uid", "kid-unknown", 3600).compact();

            assertThatThrownBy(() -> service.validateToken(token)).isInstanceOf(InvalidJwtException.class);
        }

        /** Ataque de confusión de algoritmo: un HS256 (firmado con cualquier secreto) no debe aceptarse. */
        @Test
        void hmacToken_isRejectedInFirebaseMode() {
            when(jwks.getKey(anyString())).thenReturn(googleKeys.getPublic());
            String token = Jwts.builder()
                    .header().keyId("kid-1").and()
                    .issuer(ISSUER).audience().add(PROJECT_ID).and().subject("uid")
                    .expiration(inSeconds(3600)).signWith(localKey()).compact();

            assertThatThrownBy(() -> service.validateToken(token))
                    .isInstanceOf(InvalidJwtException.class).hasMessageContaining("Algoritmo");
        }

        @Test
        void projectNotConfigured_rejectsEveryToken() {
            JwtService unconfigured = firebaseService(jwks, "");
            String token = firebaseToken("uid", "kid-1", 3600).compact();

            assertThatThrownBy(() -> unconfigured.validateToken(token))
                    .isInstanceOf(InvalidJwtException.class).hasMessageContaining("Firebase");
        }

        @Test
        void tokenFromAnotherFirebaseProject_isRejectedByConfiguredProject() {
            JwtService otherProject = firebaseService(jwks, "otro-proyecto");
            String token = firebaseToken("uid", "kid-1", 3600).compact(); // emitido para PROJECT_ID

            assertThatThrownBy(() -> otherProject.validateToken(token)).isInstanceOf(InvalidJwtException.class);
        }

        /** Token con la forma de un ID token real de Firebase, firmado con la "clave de Google" de la prueba. */
        private JwtBuilder firebaseToken(String uid, String kid, long ttlSeconds) {
            return baseFirebaseBuilder(uid, kid, ttlSeconds).issuer(ISSUER).audience().add(PROJECT_ID).and();
        }

        private JwtBuilder baseFirebaseBuilder(String uid, String kid, long ttlSeconds) {
            JwtBuilder builder = Jwts.builder()
                    .header().keyId(kid).and()
                    .claim("auth_time", Instant.now().minusSeconds(60).getEpochSecond())
                    .claim("user_id", uid)
                    .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                    .expiration(inSeconds(ttlSeconds));
            if (uid != null) {
                builder.subject(uid);
            }
            return builder.signWith(googleKeys.getPrivate(), Jwts.SIG.RS256);
        }
    }

    // ================================================================== helpers

    private static JwtService localService() {
        JwtConfig config = new JwtConfig();
        config.setMode(JwtConfig.Mode.LOCAL);
        config.setLocalSecret(LOCAL_SECRET);
        JwtService service = new JwtService(config, mock(JwksKeyProvider.class));
        service.init();
        return service;
    }

    private static JwtService firebaseService(JwksKeyProvider jwks, String projectId) {
        JwtConfig config = new JwtConfig();
        config.setMode(JwtConfig.Mode.FIREBASE);
        config.setProjectId(projectId);
        JwtService service = new JwtService(config, jwks);
        service.init();
        return service;
    }

    private static JwtBuilder localBuilder(String uid, long ttlSeconds) {
        return Jwts.builder().subject(uid)
                .issuedAt(new Date()).expiration(inSeconds(ttlSeconds)).signWith(localKey());
    }

    private static SecretKey localKey() {
        return Keys.hmacShaKeyFor(LOCAL_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private static Date inSeconds(long seconds) {
        return new Date(System.currentTimeMillis() + seconds * 1000);
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
